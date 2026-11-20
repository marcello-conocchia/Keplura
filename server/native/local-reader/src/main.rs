//! Linux-only, read-only snapshot reader. No shell, network, or path-based ACL lookup.
use std::ffi::CString;
use std::fs::{File, Metadata, OpenOptions};
use std::io::{self, Read, Write};
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::{Component, Path};

fn refused() -> io::Error { io::Error::other("local snapshot refused") }

/// Ablehnungsgrund, den Java dem Admin erklären darf (nur ein Code, nie Pfade oder Inhalte).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Reason { UnsupportedFilesystem = 3, ExtendedAcl = 4 }
impl std::fmt::Display for Reason {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { write!(f, "local snapshot refused ({})", *self as i32) }
}
impl std::error::Error for Reason {}
fn because(reason: Reason) -> io::Error { io::Error::other(reason) }
fn reason_of(error: &io::Error) -> Option<Reason> { error.get_ref().and_then(|e| e.downcast_ref::<Reason>()).copied() }
fn supported_filesystem(kind: libc::c_long) -> bool {
    [libc::EXT4_SUPER_MAGIC, libc::XFS_SUPER_MAGIC, libc::BTRFS_SUPER_MAGIC,
        libc::TMPFS_MAGIC, libc::OVERLAYFS_SUPER_MAGIC].contains(&kind)
}
fn open_at(parent: &File, name: &std::ffi::OsStr, directory: bool) -> io::Result<File> {
    let name = CString::new(name.as_bytes()).map_err(|_| refused())?;
    let flags = libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK
        | if directory { libc::O_DIRECTORY } else { 0 };
    // SAFETY: parent stays alive; name is NUL-terminated; ownership of the new FD transfers once.
    let fd = unsafe { libc::openat(parent.as_raw_fd(), name.as_ptr(), flags) };
    if fd < 0 { return Err(io::Error::last_os_error()); }
    Ok(unsafe { File::from_raw_fd(fd) })
}
fn mode_only(file: &File) -> io::Result<Metadata> {
    let metadata = file.metadata()?;
    let mut stat = std::mem::MaybeUninit::<libc::statfs>::uninit();
    // SAFETY: stat is writable, and only assumed initialized after successful fstatfs.
    if unsafe { libc::fstatfs(file.as_raw_fd(), stat.as_mut_ptr()) } != 0 { return Err(refused()); }
    let kind = unsafe { stat.assume_init() }.f_type;
    if !supported_filesystem(kind) { return Err(because(Reason::UnsupportedFilesystem)); }
    // A POSIX access ACL with a mask/named entry must never become a mode-bit grant.
    // SAFETY: querying size only; the xattr name is static and NUL-terminated.
    let size = unsafe { libc::fgetxattr(file.as_raw_fd(), c"system.posix_acl_access".as_ptr(), std::ptr::null_mut(), 0) };
    if size >= 0 { return Err(because(Reason::ExtendedAcl)); }
    let error = io::Error::last_os_error().raw_os_error();
    if error != Some(libc::ENODATA) && error != Some(libc::EOPNOTSUPP) { return Err(refused()); }
    Ok(metadata)
}
fn same(a: &Metadata, b: &Metadata) -> bool {
    a.dev() == b.dev() && a.ino() == b.ino() && a.mode() == b.mode()
        && a.uid() == b.uid() && a.gid() == b.gid() && a.len() == b.len()
        && a.mtime() == b.mtime() && a.mtime_nsec() == b.mtime_nsec()
        && a.ctime() == b.ctime() && a.ctime_nsec() == b.ctime_nsec()
}
struct Snapshot { file: File, metadata: Metadata, parents: Vec<(File, Metadata)> }
impl Snapshot {
    fn open(root: &Path, relative: &Path) -> io::Result<Self> {
        if !root.is_absolute() || relative.is_absolute() { return Err(refused()); }
        let mut directory = OpenOptions::new().read(true)
            .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC).open("/")?;
        for part in root.components() {
            match part {
                Component::RootDir => (),
                Component::Normal(name) => directory = open_at(&directory, name, true)?,
                _ => return Err(refused()),
            }
        }
        let parts: Vec<_> = relative.components().collect();
        if parts.is_empty() || parts.len() > 64 { return Err(refused()); }
        let mut parents = Vec::new();
        for (i, part) in parts.iter().enumerate() {
            let Component::Normal(name) = part else { return Err(refused()); };
            let metadata = mode_only(&directory)?;
            let next = open_at(&directory, name, i + 1 < parts.len())?;
            parents.push((directory, metadata));
            if i + 1 == parts.len() {
                let metadata = mode_only(&next)?;
                if !metadata.is_file() { return Err(refused()); }
                return Ok(Self { file: next, metadata, parents });
            }
            directory = next;
        }
        Err(refused())
    }
    fn validate(&self) -> io::Result<()> {
        if !same(&self.metadata, &mode_only(&self.file)?) { return Err(refused()); }
        for (file, metadata) in &self.parents {
            if !same(metadata, &mode_only(file)?) { return Err(refused()); }
        }
        Ok(())
    }
    fn emit(&mut self, output: &mut impl Write, contents: bool, maximum: u64) -> io::Result<()> {
        self.validate()?;
        if contents && self.metadata.len() > maximum { return Err(refused()); }
        writeln!(output, "ICLR1")?;
        writeln!(output, "FILE {} {} {} {} {} {}", self.metadata.mode(), self.metadata.uid(),
            self.metadata.gid(), self.metadata.len(), self.metadata.mtime(), self.metadata.mtime_nsec())?;
        for (_, metadata) in &self.parents {
            writeln!(output, "DIR {} {} {}", metadata.mode(), metadata.uid(), metadata.gid())?;
        }
        writeln!(output, "END")?;
        if contents {
            let mut bytes = [0; 65536];
            let mut copied = 0u64;
            loop {
                let n = self.file.read(&mut bytes)?;
                if n == 0 { break; }
                copied = copied.checked_add(n as u64).ok_or_else(refused)?;
                if copied > maximum { return Err(refused()); }
                output.write_all(&bytes[..n])?;
            }
            if copied != self.metadata.len() { return Err(refused()); }
        }
        self.validate()?; // caller must discard ALL bytes unless we exit successfully
        output.flush()
    }
}
fn run() -> io::Result<()> {
    let args: Vec<_> = std::env::args_os().collect();
    if args.len() != 5 { return Err(refused()); }
    let contents = match args[1].to_str() { Some("snapshot") => true, Some("acl") => false, _ => return Err(refused()) };
    let maximum = args[4].to_str().and_then(|s| s.parse::<u64>().ok()).ok_or_else(refused)?;
    let mut snapshot = Snapshot::open(Path::new(&args[2]), Path::new(&args[3]))?;
    snapshot.emit(&mut io::stdout().lock(), contents, maximum)
}
fn main() {
    if let Err(error) = run() {
        eprintln!("local snapshot refused"); // no paths or source contents in diagnostics
        // 3 = Dateisystem nicht sicher auswertbar (z. B. NFS), 4 = erweiterte ACL, sonst 1
        std::process::exit(reason_of(&error).map_or(1, |r| r as i32));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::os::unix::fs::{PermissionsExt, symlink};
    use std::sync::atomic::{AtomicUsize, Ordering};
    static NEXT: AtomicUsize = AtomicUsize::new(0);
    struct Fixture(std::path::PathBuf);
    impl Fixture {
        fn new() -> Self {
            let path = std::env::temp_dir().join(format!("keplura-reader-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
            fs::create_dir(&path).unwrap(); Self(path)
        }
    }
    impl Drop for Fixture { fn drop(&mut self) { let _ = fs::remove_dir_all(&self.0); } }
    #[test] fn leaf_replacement_never_pairs_old_acl_with_new_bytes() {
        let root = Fixture::new(); let path = root.0.join("doc.txt");
        fs::write(&path, b"PUBLIC").unwrap(); fs::set_permissions(&path, fs::Permissions::from_mode(0o644)).unwrap();
        let mut snapshot = Snapshot::open(&root.0, Path::new("doc.txt")).unwrap();
        let next = root.0.join("next"); fs::write(&next, b"PRIVATE").unwrap();
        fs::set_permissions(&next, fs::Permissions::from_mode(0o600)).unwrap(); fs::rename(&next, &path).unwrap();
        let mut bytes = Vec::new();
        let result = snapshot.emit(&mut bytes, true, 1000);
        // Parent change may reject; in neither case can PRIVATE be emitted as PUBLIC.
        assert!(!bytes.windows(7).any(|s| s == b"PRIVATE"));
        if result.is_ok() { assert!(bytes.ends_with(b"PUBLIC")); }
    }
    #[test] fn replaced_parent_cannot_redirect_the_open_snapshot() {
        let root = Fixture::new(); fs::create_dir(root.0.join("inside")).unwrap();
        fs::write(root.0.join("inside/doc"), b"PUBLIC").unwrap();
        let mut snapshot = Snapshot::open(&root.0, Path::new("inside/doc")).unwrap();
        fs::rename(root.0.join("inside"), root.0.join("old")).unwrap();
        fs::create_dir(root.0.join("inside")).unwrap(); fs::write(root.0.join("inside/doc"), b"PRIVATE").unwrap();
        let mut bytes = Vec::new(); let _ = snapshot.emit(&mut bytes, true, 1000);
        assert!(!bytes.windows(7).any(|s| s == b"PRIVATE"));
    }
    #[test] fn changes_to_the_open_inode_are_refused() {
        let root = Fixture::new(); fs::write(root.0.join("doc"), b"original").unwrap();
        let mut snapshot = Snapshot::open(&root.0, Path::new("doc")).unwrap();
        fs::write(root.0.join("doc"), b"changed length").unwrap();
        assert!(snapshot.emit(&mut Vec::new(), true, 1000).is_err());
    }
    #[test] fn modification_during_copy_invalidates_the_entire_output() {
        struct MutatingWriter { path: std::path::PathBuf, bytes: Vec<u8>, changed: bool }
        impl Write for MutatingWriter {
            fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
                if bytes.len() == 65536 && !self.changed {
                    fs::set_permissions(&self.path, fs::Permissions::from_mode(0o600))?;
                    self.changed = true;
                }
                self.bytes.extend_from_slice(bytes); Ok(bytes.len())
            }
            fn flush(&mut self) -> io::Result<()> { Ok(()) }
        }
        let root = Fixture::new(); let path = root.0.join("doc");
        fs::write(&path, vec![b'a'; 131072]).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o644)).unwrap();
        let mut snapshot = Snapshot::open(&root.0, Path::new("doc")).unwrap();
        let mut output = MutatingWriter { path, bytes: Vec::new(), changed: false };
        assert!(snapshot.emit(&mut output, true, 200000).is_err());
        assert!(output.changed); // caller observes failure even after some bytes were emitted
    }

    #[test] fn symlinks_and_parent_traversal_are_refused() {
        let root = Fixture::new(); fs::write(root.0.join("doc"), b"data").unwrap();
        symlink("doc", root.0.join("link")).unwrap(); symlink(&root.0, root.0.join("directory-link")).unwrap();
        for name in ["link", "directory-link/doc", "../outside", "/etc/passwd"] {
            assert!(Snapshot::open(&root.0, Path::new(name)).is_err());
        }
    }
    #[test] fn extended_acl_and_read_limit_are_refused() {
        let root = Fixture::new(); let path = root.0.join("doc"); fs::write(&path, b"data").unwrap();
        assert!(Snapshot::open(&root.0, Path::new("doc")).unwrap().emit(&mut Vec::new(), true, 3).is_err());
        let status = std::process::Command::new("setfacl").args(["-m", "u:2001:r--,g::---,m::r--,o::---", "--"]).arg(&path).status().unwrap();
        assert!(status.success());
        let refused = Snapshot::open(&root.0, Path::new("doc")).err().expect("extended ACL must be refused");
        assert_eq!(reason_of(&refused), Some(Reason::ExtendedAcl));
    }
    #[test] fn only_local_filesystems_are_supported_and_reasons_survive_io_errors() {
        assert!(supported_filesystem(libc::EXT4_SUPER_MAGIC));
        assert!(!supported_filesystem(libc::NFS_SUPER_MAGIC));
        assert!(!supported_filesystem(libc::SMB_SUPER_MAGIC));
        assert_eq!(reason_of(&because(Reason::UnsupportedFilesystem)), Some(Reason::UnsupportedFilesystem));
        assert_eq!(reason_of(&refused()), None);
        assert_eq!(Reason::UnsupportedFilesystem as i32, 3);
        assert_eq!(Reason::ExtendedAcl as i32, 4);
    }
}
