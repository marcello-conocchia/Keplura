#!/usr/bin/env node
'use strict';
// Run after quality-check.cjs against the same local pilot database.
const fs=require('node:fs');
const assert=require('node:assert/strict');
const {arg,createClient}=require('./check-client.cjs');
const report={startedAt:new Date().toISOString(),steps:[]};
function save(){if(arg('out'))fs.writeFileSync(arg('out'),JSON.stringify(report,null,2)+'\n');}
function step(name){report.steps.push({name,ok:true});save();console.log(JSON.stringify({name,ok:true}));}
async function main(){
  const fixture=JSON.parse(fs.readFileSync(arg('quality-report'),'utf8')).objects;
  const {json,request,login}=createClient();await login();
  const org=fixture.orgId,space=fixture.spaceId,kb=fixture.knowledgeBaseId;
  const root=`/api/v1/orgs/${org}/spaces/${space}/knowledge-bases`;
  const transcript=await json(`/api/v1/orgs/${org}/chat/conversations/${fixture.conversationId}`);
  const citation=transcript.messages.findLast(m=>m.role==='assistant').citations[0];
  const docRoot=`${root}/${kb}/documents/${citation.documentId}`;
  const response=await request(docRoot+`/preview?chunkIdx=${citation.chunkIdx}&startOffset=${citation.startOffset}`);
  assert.equal(response.status,200);assert.equal(response.headers.get('cache-control'),'no-store');
  assert.equal(response.headers.get('x-content-type-options'),'nosniff');
  const preview=await response.json();
  assert(preview.exactLocation && preview.blocks.some(b=>b.chunkIdx===citation.chunkIdx && b.sectionTitle==='Leitwarte' && b.text.includes('42')));
  assert(!JSON.stringify(preview).includes('storedPath'));
  step('Abschnittsvorschau liefert die belegende Textstelle ohne Dateipfade');
  assert.equal((await request(docRoot+`/preview?chunkIdx=${citation.chunkIdx}&startOffset=${citation.startOffset+1}`)).status,404);
  assert.equal((await request(docRoot+'/preview?chunkIdx=-1')).status,400);
  assert.equal((await request(docRoot.replace(`/orgs/${org}/`,`/orgs/99999999/`)+'/preview')).status,404);
  // No cookies or CSRF values are included in this independent anonymous call.
  const base=new URL(arg('base','http://localhost:18080')).origin;
  assert.equal((await fetch(base+docRoot+'/preview')).status,401);
  step('Alte Position, fremder Pfad und fehlende Anmeldung werden zurückgewiesen');
  const pdfKb=fixture.pdfKnowledgeBaseId;
  assert(pdfKb,'Vorher quality-check.cjs mit --pdf ausführen.');
  const conv=await json(`/api/v1/orgs/${org}/spaces/${space}/chat/conversations`,{method:'POST',body:{knowledgeBaseId:pdfKb,title:'PDF-Quelle: Seite 2'}});
  const turn=await request(`/api/v1/orgs/${org}/chat/conversations/${conv.id}/stream`,{method:'POST',body:{prompt:'Wie hoch ist die Rauchwarnschwelle der Leitwarte? Antworte kurz.'},timeout:600000});
  assert(turn.ok);await turn.text();
  const pdfTranscript=await json(`/api/v1/orgs/${org}/chat/conversations/${conv.id}`);
  const answer=pdfTranscript.messages.findLast(m=>m.role==='assistant');
  const pdfCitation=answer.citations.find(c=>c.pageNumber===2);
  assert(answer.status==='complete' && answer.content.includes('42') && pdfCitation);
  const pdfPreview=await json(`${root}/${pdfKb}/documents/${pdfCitation.documentId}/preview?chunkIdx=${pdfCitation.chunkIdx}&pageNumber=2&startOffset=${pdfCitation.startOffset}`);
  assert(pdfPreview.blocks.some(b=>b.pageNumber===2 && b.chunkIdx===pdfCitation.chunkIdx && b.text.includes('42')));
  report.objects={...fixture,pdfConversationId:conv.id,pdfDocumentId:pdfCitation.documentId};
  step('PDF-Chatquelle öffnet die echte Seite 2 und den zitierten Absatz');
  // Deliberately hostile text is a fixture for the browser's text-only rendering.
  const form=new FormData();
  form.set('file',new Blob(["# HTML-Beispiel\nDie Beispielnummer lautet 73.\n<script>window.kepluraPreviewInjected=true</script>\n"]),'html-beispiel.md');
  const hostile=await json(`${root}/${kb}/documents`,{method:'POST',body:form});
  let indexed=false;
  for(let i=0;i<90;i++){
    const docs=await json(`${root}/${kb}/documents`);
    if(docs.some(d=>d.id===hostile.id && d.indexStatus==='INDEXED')){indexed=true;break;}
    await new Promise(resolve=>setTimeout(resolve,2000));
  }
  assert(indexed);
  const htmlConv=await json(`/api/v1/orgs/${org}/spaces/${space}/chat/conversations`,{method:'POST',body:{knowledgeBaseId:kb,title:'Textvorschau: HTML-Beispiel'}});
  const htmlTurn=await request(`/api/v1/orgs/${org}/chat/conversations/${htmlConv.id}/stream`,{method:'POST',body:{prompt:'Wie lautet die Beispielnummer im HTML-Beispiel? Antworte kurz.'},timeout:600000});
  assert(htmlTurn.ok);await htmlTurn.text();
  const htmlTranscript=await json(`/api/v1/orgs/${org}/chat/conversations/${htmlConv.id}`);
  assert(htmlTranscript.messages.findLast(m=>m.role==='assistant').citations.some(c=>c.documentId===hostile.id));
  report.objects.htmlConversationId=htmlConv.id;
  step('HTML-Beispiel für die reine Textdarstellung bereitgestellt');
  report.ok=true;report.completedAt=new Date().toISOString();save();
}
main().catch(error=>{report.ok=false;report.error=error instanceof assert.AssertionError?error.message:'Prüfumgebung oder Verbindung nicht bereit.';save();console.error(report.error);process.exitCode=1;});
