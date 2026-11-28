package com.keplura.server.tool;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Org-scoped tools administration; platform-wide MCP configuration grants no cross-org trust. */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/tools/settings")
public class ToolSettingsController {
    /** {@code kind}: {@code mcp} or {@code openapi} (TU-06a); server ids are only unique within one kind. */
    public record Boundary(long serverId,String name,boolean internal,String kind) { }
    /** {@code webSearchAvailable}: a search provider is configured by the platform admin (TU-04d). */
    public record Settings(boolean knowledgeEnabled,boolean webSearchEnabled,boolean webSearchAvailable,List<Boundary> servers) { }
    public record Input(Boolean knowledgeEnabled,Boolean webSearchEnabled,Long serverId,Boolean internal,String kind) {
        public Input(Boolean knowledgeEnabled,Boolean webSearchEnabled,Long serverId,Boolean internal) {
            this(knowledgeEnabled,webSearchEnabled,serverId,internal,null);
        }
    }
    private final JdbcTemplate jdbc;
    private com.keplura.server.websearch.WebSearchService webSearch;
    public ToolSettingsController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void setWebSearch(com.keplura.server.websearch.WebSearchService webSearch) { this.webSearch=webSearch; }

    private long owner(long orgId,HttpServletRequest request) {
        var session=request.getSession(false);
        var principal=session==null?null:session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if(!(principal instanceof AuthPrincipal p)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Integer count=jdbc.queryForObject("select count(*) from membership where org_id=? and user_id=? and role='OWNER'",Integer.class,orgId,p.userId());
        if(count==null || count!=1) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return p.userId();
    }
    private List<Boundary> boundaries(long orgId) {
        var out=new java.util.ArrayList<>(boundaries(orgId,"mcp"));
        out.addAll(boundaries(orgId,"openapi"));
        return out;
    }
    /** Table names come only from this fixed switch, never from input. */
    private List<Boundary> boundaries(long orgId,String kind) {
        String server=kind.equals("openapi")?"openapi_server":"mcp_server";
        return jdbc.query("""
            select s.id,s.name,coalesce(b.internal,false) from %1$s s
            left join %2$s b on b.server_id=s.id and b.org_id=?
            where s.all_spaces=true or exists(select 1 from %3$s x join space p on p.id=x.space_id where x.server_id=s.id and p.org_id=?)
            order by s.id""".formatted(server,boundaryTable(kind),server+"_space"),
            (rs,n)->new Boundary(rs.getLong(1),rs.getString(2),rs.getBoolean(3),kind),orgId,orgId);
    }
    private static String boundaryTable(String kind) { return kind.equals("openapi")?"openapi_org_boundary":"mcp_org_boundary"; }
    @GetMapping
    public Settings get(@PathVariable long orgId,HttpServletRequest request) {
        owner(orgId,request);
        var flags=jdbc.query("select knowledge_enabled,web_search_enabled from org_tool_setting where org_id=?",
                (rs,n)->new boolean[]{rs.getBoolean(1),rs.getBoolean(2)},orgId);
        boolean available=false;
        try { available=webSearch!=null && webSearch.enabled(); }
        catch(RuntimeException unreadable) { /* shown as unavailable; the tool itself fails closed */ }
        return new Settings(!flags.isEmpty() && flags.getFirst()[0],!flags.isEmpty() && flags.getFirst()[1],available,boundaries(orgId));
    }
    @PutMapping
    @Transactional
    public Settings put(@PathVariable long orgId,@RequestBody Input input,HttpServletRequest request) {
        long userId=owner(orgId,request);
        // Same org row serializes settings changes without races in insert/update or revision.
        jdbc.queryForObject("select id from organization where id=? for update",Long.class,orgId);
        if(input.knowledgeEnabled()!=null) {
            int changed=jdbc.update("update org_tool_setting set knowledge_enabled=?,revision=revision+1 where org_id=? and knowledge_enabled<>?",input.knowledgeEnabled(),orgId,input.knowledgeEnabled());
            if(jdbc.queryForObject("select count(*) from org_tool_setting where org_id=?",Integer.class,orgId)==0) {
                jdbc.update("insert into org_tool_setting(org_id,knowledge_enabled,revision) values(?,?,1)",orgId,input.knowledgeEnabled()); changed=1;
            }
            if(changed>0) audit(orgId,userId,null,"knowledge_search",input.knowledgeEnabled());
        }
        if(input.webSearchEnabled()!=null) {
            // Own revision: switching web search never changes (or cancels) the knowledge tools.
            int changed=jdbc.update("update org_tool_setting set web_search_enabled=?,web_revision=web_revision+1 where org_id=? and web_search_enabled<>?",input.webSearchEnabled(),orgId,input.webSearchEnabled());
            if(jdbc.queryForObject("select count(*) from org_tool_setting where org_id=?",Integer.class,orgId)==0) {
                jdbc.update("insert into org_tool_setting(org_id,web_search_enabled,web_revision) values(?,?,1)",orgId,input.webSearchEnabled()); changed=1;
            }
            if(changed>0) audit(orgId,userId,null,"web_search",input.webSearchEnabled());
        }
        if(input.serverId()!=null && input.internal()!=null) {
            String kind=input.kind()==null?"mcp":input.kind();
            if(!kind.equals("mcp") && !kind.equals("openapi")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            String table=boundaryTable(kind);
            if(boundaries(orgId,kind).stream().noneMatch(b -> b.serverId()==input.serverId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            int changed=jdbc.update("update "+table+" set internal=?,revision=revision+1 where org_id=? and server_id=? and internal<>?",input.internal(),orgId,input.serverId(),input.internal());
            if(jdbc.queryForObject("select count(*) from "+table+" where org_id=? and server_id=?",Integer.class,orgId,input.serverId())==0) {
                jdbc.update("insert into "+table+"(org_id,server_id,internal,revision) values(?,?,?,1)",orgId,input.serverId(),input.internal()); changed=1;
            }
            if(changed>0) {
                audit(orgId,userId,input.serverId(),kind+"_boundary",input.internal());
                jdbc.update("update tool_call set status='CANCELLED',decided_at=? where org_id=? and adapter=? and source_id=? and status='PENDING'",Timestamp.from(Instant.now()),orgId,kind,input.serverId());
            }
        }
        return get(orgId,request);
    }
    private void audit(long org,long user,Long server,String action,boolean enabled) {
        jdbc.update("insert into tool_admin_event(org_id,user_id,server_id,action,enabled,created_at) values(?,?,?,?,?,?)",org,user,server,action,enabled,Timestamp.from(Instant.now()));
    }
}
