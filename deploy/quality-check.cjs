#!/usr/bin/env node
'use strict';
// Real installed PostgreSQL + BGE cross-encoder + Ollama acceptance. Creates
// its own example organisation, kept for subsequent browser inspection.
const fs = require('node:fs');
const assert = require('node:assert/strict');
const {arg, createClient} = require('./check-client.cjs');
const report = {startedAt:new Date().toISOString(), steps:[]};
function save() { const out = arg('out'); if (out) fs.writeFileSync(out, JSON.stringify(report,null,2)+'\n'); }
function step(name, extra={}) { report.steps.push({name,ok:true,...extra}); save(); console.log(JSON.stringify({name,ok:true,...extra})); }
async function main() {
  const {json,request,login} = createClient();
  await login();
  const org = await json('/api/v1/orgs', {method:'POST',body:{name:`Antwortqualität ${new Date().toISOString()}`}});
  const spaceRoot = `/api/v1/orgs/${org.id}/spaces`;
  const space = await json(spaceRoot, {method:'POST',body:{name:'Qualitätsprüfung'}});
  const kbRoot = `${spaceRoot}/${space.id}/knowledge-bases`;
  const kb = await json(kbRoot, {method:'POST',body:{name:'Betriebshandbuch'}});
  const root = `${kbRoot}/${kb.id}`;
  report.objects = {orgId:org.id,spaceId:space.id,knowledgeBaseId:kb.id};
  async function upload(where, filename, bytes) {
    const form = new FormData();
    form.set('file', new Blob([bytes]), filename);
    const doc = await json(where+'/documents',{method:'POST',body:form});
    for (let attempt=0; attempt<180; attempt++) {
      const list = await json(where+'/documents');
      const current = list.find(item=>item.id===doc.id);
      assert(current?.indexStatus!=='FAILED','Dokumentverarbeitung fehlgeschlagen.');
      if (current?.indexStatus==='INDEXED') return doc;
      await new Promise(resolve=>setTimeout(resolve,2000));
    }
    assert.fail('Verarbeitung nach sechs Minuten noch nicht abgeschlossen.');
  }
  const doc = await upload(root, 'betriebshandbuch.md', '# Leitwarte\nDie Rauchwarnschwelle der Leitwarte beträgt genau 42 Prozent.\n\n# Urlaub\nUrlaub muss bei der Teamleitung beantragt werden.\n');
  report.objects.documentId=doc.id;
  const query='Wie hoch ist die Rauchwarnschwelle der Leitwarte?';
  const search=await json(root+'/search',{method:'POST',body:{query,topK:4}});
  const hit=search.results.find(hit=>hit.documentId===doc.id && hit.text.includes('42'));
  assert.equal(search.backend,'pgvector');
  assert(hit && hit.score>=0.5 && hit.sectionTitle==='Leitwarte');
  assert(Number.isInteger(hit.startOffset) && hit.endOffset>hit.startOffset);
  step('Hybride Suche und echter Reranker finden belegte Textstelle',{score:hit.score,section:hit.sectionTitle});
  const unrelated='Welche Farbe hat der Dienstwagen der Geschäftsführung?';
  const noMatches=await json(root+'/search',{method:'POST',body:{query:unrelated,topK:4}});
  assert.equal(noMatches.results.length,0,'Unpassende Textstellen nicht ausgefiltert.');
  step('Unpassende Fundstellen werden verworfen');
  // The wrong organisation path must fail before any retrieval or model work.
  const other=await json('/api/v1/orgs',{method:'POST',body:{name:'Falscher Organisationspfad'}});
  const wrong=await request(`/api/v1/orgs/${other.id}/spaces/${space.id}/knowledge-bases/${kb.id}/search`,{method:'POST',body:{query,topK:4}});
  assert.equal(wrong.status,404);
  step('Falscher Organisationspfad bleibt unsichtbar');
  async function conversation(kbId,title) {
    return json(`${spaceRoot}/${space.id}/chat/conversations`,{method:'POST',body:{knowledgeBaseId:kbId,title}});
  }
  async function turn(conv,prompt) {
    const path=`/api/v1/orgs/${org.id}/chat/conversations/${conv.id}`;
    const response=await request(path+'/stream',{method:'POST',body:{prompt},timeout:600000});
    assert(response.ok,`Antwort konnte nicht gestartet werden: HTTP ${response.status}`);
    const text=await response.text();
    assert(text.includes('event: turn') && text.includes('"complete"'),'Antwortübertragung nicht vollständig.');
    return (await json(path)).messages.findLast(item=>item.role==='assistant');
  }
  const grounded=await conversation(kb.id,'Belegt: Leitwarte');
  const answer=await turn(grounded,query+' Antworte kurz.');
  assert(answer.content.includes('42'));
  assert(answer.citations.some(c=>c.documentId===doc.id && c.sectionTitle==='Leitwarte'));
  report.answer=answer.content; report.objects.conversationId=grounded.id;
  step('Echte Antwort trägt eine Abschnittsquelle');
  const unknown=await conversation(kb.id,'Kein Beleg: Dienstwagen');
  const refusal=await turn(unknown,unrelated);
  assert(refusal.content.startsWith('Dazu habe ich nichts gefunden.'));
  assert.equal(refusal.model,'Wissenssuche'); assert.equal(refusal.citations.length,0);
  report.objects.refusalConversationId=unknown.id; report.refusal=refusal.content;
  step('Unbeantwortbare Frage erhält gespeicherten Nichtfund ohne Modellantwort');
  const empty=await json(kbRoot,{method:'POST',body:{name:'Noch ohne Dokumente'}});
  const emptyAnswer=await turn(await conversation(empty.id,'Leere Wissensbasis'),query);
  assert.equal(emptyAnswer.model,'Wissenssuche'); assert.equal(emptyAnswer.citations.length,0);
  step('Leere Wissensbasis führt ebenfalls zu ehrlichem Nichtfund');
  if (arg('pdf')) {
    const pdfKb=await json(kbRoot,{method:'POST',body:{name:'PDF-Handbuch'}});
    const pdfRoot=`${kbRoot}/${pdfKb.id}`;
    const pdf=await upload(pdfRoot,'betriebshandbuch.pdf',fs.readFileSync(arg('pdf')));
    const pageResult=await json(pdfRoot+'/search',{method:'POST',body:{query,topK:4}});
    assert(pageResult.results.some(hit=>hit.documentId===pdf.id && hit.pageNumber===2 && hit.text.includes('42')));
    report.objects.pdfKnowledgeBaseId=pdfKb.id;
    step('Echter PDF-Upload nennt die belegende Seite 2');
  }
  report.ok=true; report.completedAt=new Date().toISOString(); save();
}
main().catch(error=>{
  report.ok=false;
  report.error=error instanceof assert.AssertionError ? error.message : 'Verbindung oder Prüfumgebung nicht bereit.';
  save(); console.error(report.error); process.exitCode=1;
});
