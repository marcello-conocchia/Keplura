package com.keplura.server.tool;

import com.keplura.server.chat.ChatProperties;
import com.keplura.server.search.SearchHit;
import com.keplura.server.search.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Guessed locations never reach document storage; only this question's successful search grants an anchor. */
class DocumentReadToolTest {
    private static final JsonMapper M=JsonMapper.builder().build();
    private final AtomicBoolean contextUnavailable=new AtomicBoolean();
    private JdbcTemplate jdbc;
    private SearchService search;
    private KnowledgeSearchAdapter adapter;
    private ToolExecutionService service;
    private int anchor=3;
    private String chunkText="private neighbor";

    @BeforeEach void setup() {
        var ds=new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:document-read-"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(ds) {
            @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
                if(contextUnavailable.get() && sql.contains("select c.space_id"))
                    throw new org.springframework.dao.DataAccessResourceFailureException("temporary context failure");
                return super.query(sql,mapper,args);
            }
        };
        jdbc.update("insert into organization(id,name,created_at) values(1,'Org',current_timestamp)");
        jdbc.update("insert into space(id,org_id,name,created_at) values(1,1,'Space',current_timestamp)");
        jdbc.update("insert into membership(org_id,user_id,role,created_at) values(1,1,'OWNER',current_timestamp)");
        jdbc.update("insert into chat_conversation(id,org_id,space_id,created_by,title,created_at,updated_at,knowledge_base_id) values(1,1,1,1,'Chat',current_timestamp,current_timestamp,11)");
        jdbc.update("insert into org_tool_setting(org_id,knowledge_enabled) values(1,true)");
        message(1);
        search=mock(SearchService.class);
        when(search.search(anyLong(),anyLong(),anyLong(),anyLong(),anyString(),anyInt())).thenAnswer(i ->
                new SearchService.SearchResponse("alpha",1,"fixture","fixture",List.of(hit(anchor))));
        when(search.resolveHit(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyInt(),anyDouble()))
                .thenAnswer(i -> hit(i.getArgument(5)));
        when(search.resolveWindow(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyInt(),anyInt(),anyDouble()))
                .thenAnswer(i -> java.util.stream.IntStream.rangeClosed(i.getArgument(5),Math.min(8,i.getArgument(6))).mapToObj(this::hit).toList());
        var chat=mock(ChatProperties.class); when(chat.contextChunkChars()).thenReturn(4000);
        adapter=new KnowledgeSearchAdapter(jdbc,search,chat,4000);
        service=new ToolExecutionService(jdbc,List.of(adapter),new ToolAuditLog(jdbc));
    }
    private SearchHit hit(int idx) { return new SearchHit(100L,"document.txt",idx,chunkText,1); }
    private void message(long id) {
        jdbc.update("insert into chat_message(id,conversation_id,role,content,model,citations_json,status,created_at) values(?,1,'user','alpha','','[]','complete',current_timestamp)",id);
    }
    private ToolExecutionService.ToolTurn turn(long prompt) {
        var turn=service.openTurn(service.resolveContext(1L,1L,1L),prompt);
        turn.startQuestion("alpha",""); return turn;
    }
    private ToolExecutionService.ToolTurn searched() {
        var turn=turn(1); assertThat(turn.call("knowledge_search","{\"query\":\"alpha\"}").invoked()).isTrue(); return turn;
    }
    private String args(int idx,int before,int after) {
        return M.createObjectNode().put("documentId",100).put("chunkIdx",idx).put("before",before).put("after",after).toString();
    }

    @Test void defaultWindowReadsNeighborsAndPersistsOnlyReferences() {
        var turn=searched();
        assertThat(turn.call("document_read","{\"documentId\":100,\"chunkIdx\":3}").invoked()).isTrue();
        verify(search).resolveWindow(1L,1L,1L,11L,100L,2,4,1);
        String stored=jdbc.queryForObject("select result_text from tool_call where tool_name='document_read'",String.class);
        assertThat(M.readTree(stored)).hasSize(3);
        assertThat(stored).doesNotContain("private neighbor");
        assertThat(service.cards(1L,1L,1L).getLast().result()).contains("Abschnitt 2","Abschnitt 4");
    }
    @ParameterizedTest @ValueSource(ints={0,3,8})
    void windowIsBoundedAtDocumentEdgesAndAtSevenChunks(int idx) {
        anchor=idx; var turn=searched();
        assertThat(turn.call("document_read",args(idx,3,3)).invoked()).isTrue();
        var result=M.readTree(jdbc.queryForObject("select result_text from tool_call where tool_name='document_read'",String.class));
        assertThat(result.size()).isEqualTo(idx==3?7:4);
    }
    @Test void largeWindowOutputStaysWithinEightThousandCharacters() {
        chunkText="x".repeat(4000); var turn=searched();
        turn.call("document_read",args(3,3,3));
        assertThat(service.cards(1L,1L,1L).getLast().result().length()).isLessThanOrEqualTo(8000);
    }
    @Test void unknownDocumentOrChunkNeverQueriesDocumentStorage() {
        var turn=searched(); clearInvocations(search);
        assertThat(turn.call("document_read","{\"documentId\":101,\"chunkIdx\":3}").invoked()).isFalse();
        verify(search,never()).resolveWindow(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyInt(),anyInt(),anyDouble());
    }
    @Test void readingANeighborDoesNotGrantAnotherReadAnchor() {
        var turn=searched(); turn.call("document_read",args(3,1,1)); clearInvocations(search);
        assertThat(turn.call("document_read",args(4,1,1)).invoked()).isFalse();
        verify(search,never()).resolveWindow(eq(1L),eq(1L),eq(1L),eq(11L),eq(100L),eq(3),eq(5),anyDouble());
    }
    @Test void hitFromAnEarlierQuestionDoesNotGrantAnAnchor() {
        searched(); message(2); var next=turn(2); clearInvocations(search);
        assertThat(next.call("document_read",args(3,1,1)).invoked()).isFalse();
        verify(search,never()).resolveWindow(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyInt(),anyInt(),anyDouble());
    }
    @Test void noSearchOrPromptMeansNoRead() {
        var turn=turn(1);
        assertThat(turn.call("document_read",args(3,1,1)).invoked()).isFalse();
        verifyNoInteractions(search);
    }
    @ParameterizedTest
    @ValueSource(strings={"{\"documentId\":100,\"chunkIdx\":3,\"kbId\":22}","{\"documentId\":100,\"chunkIdx\":-1}",
            "{\"documentId\":100,\"chunkIdx\":3,\"before\":4}","{\"documentId\":100,\"chunkIdx\":3,\"after\":-1}",
            "{\"documentId\":100,\"chunkIdx\":3,\"before\":0.5}","{\"documentId\":1e400,\"chunkIdx\":3}",
            "{\"documentId\":100,\"chunkIdx\":2147483648}","{\"documentId\":-1,\"chunkIdx\":3}"})
    void invalidArgumentsNeverQueryDocumentStorage(String raw) {
        var turn=searched(); clearInvocations(search);
        assertThat(turn.call("document_read",raw).invoked()).isFalse();
        verify(search,never()).resolveWindow(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyInt(),anyInt(),anyDouble());
    }
    @Test void foreignContextCannotBorrowSearchAnchors() {
        searched(); clearInvocations(search);
        for(var context:List.of(new ToolContext(2L,1L,1L,1L),new ToolContext(1L,2L,1L,1L),
                new ToolContext(1L,1L,2L,1L),new ToolContext(1L,1L,1L,2L))) {
            var other=service.openTurn(context,1L);
            assertThat(other.call("document_read",args(3,1,1)).invoked()).isFalse();
        }
        verify(search,never()).resolveWindow(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyInt(),anyInt(),anyDouble());
    }

    @Test void disabledOrUnassignedToolsAreNotOffered() {
        jdbc.update("update org_tool_setting set knowledge_enabled=false");
        assertThat(service.openTurn(service.resolveContext(1L,1L,1L)).offered()).isEmpty();
        jdbc.update("update org_tool_setting set knowledge_enabled=true");
        jdbc.update("update chat_conversation set knowledge_base_id=null");
        assertThat(service.openTurn(service.resolveContext(1L,1L,1L)).offered()).isEmpty();
    }
    @Test void contextDatabaseFailureIsNotEvidenceOfRevokedRights() {
        var turn=searched();
        var tool=adapter.definitionsFor(service.resolveContext(1L,1L,1L)).getFirst();
        var store=new ToolCallStore(jdbc);
        var pending=store.create(service.resolveContext(1L,1L,1L),1L,tool,ToolExecutionService.definitionHash(tool),"{}",
                ToolCallStore.Status.PENDING,true,null,java.time.Instant.now()).orElseThrow();
        contextUnavailable.set(true);
        var hidden=service.cards(1L,1L,1L).getLast();
        assertThat(hidden.status()).isEqualTo("PENDING");
        assertThat(hidden.sourceTemporarilyUnavailable()).isTrue();
        assertThat(hidden.argumentsText()).isNull();
        contextUnavailable.set(false);
        assertThat(service.cards(1L,1L,1L).getLast().status()).isEqualTo("PENDING");
        assertThat(store.find(pending.id()).orElseThrow().status()).isEqualTo(ToolCallStore.Status.PENDING);
    }
}
