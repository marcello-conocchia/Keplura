package com.keplura.server.tool;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * TU-05d: structured question to the person ({@code ask_user}). Built in, READ, internal — nothing leaves the
 * server. {@link ToolExecutionService} never calls {@link #invoke}: a call becomes a pending question card, and
 * the person's validated answer becomes the card result. Chat selection and model defaults can switch it off
 * (kind {@code ask}); it is only offered next to at least one other tool.
 */
@Component
public class UserQuestionToolAdapter implements ToolAdapter {

    public static final String KIND = ToolExecutionService.ASK_KIND;
    public static final String FUNCTION = "ask_user";
    public static final int MAX_QUESTION = 500;
    public static final int MAX_OPTIONS = 6;
    public static final int MAX_OPTION = 100;
    public static final int MAX_ANSWER = 1000;

    private static final JsonMapper M = JsonMapper.builder().build();
    private static final JsonNode SCHEMA = M.readTree("""
            {"type":"object","additionalProperties":false,"required":["question"],"properties":{
              "question":{"type":"string","minLength":1,"maxLength":%d,"description":"Kurze, eindeutige Rückfrage an die Person."},
              "options":{"type":"array","maxItems":%d,"items":{"type":"string","minLength":1,"maxLength":%d},
                "description":"Optional: Antwortmöglichkeiten zum Anklicken."},
              "allowFreeText":{"type":"boolean","description":"Ob neben den Optionen eine freie Antwort möglich ist. Ohne Optionen immer."}}}"""
            .formatted(MAX_QUESTION, MAX_OPTIONS, MAX_OPTION));

    @Override public String kind() { return KIND; }

    @Override public List<ToolDefinition> definitions(Long spaceId) { return List.of(); }

    @Override public List<ToolDefinition> definitionsFor(ToolContext ctx) {
        if (ctx == null || ctx.orgId() == null) return List.of();
        return List.of(new ToolDefinition(KIND + ":" + ctx.orgId() + ":" + FUNCTION, KIND, FUNCTION, FUNCTION, ctx.orgId(),
                "Rückfrage", "Stelle der Person eine Rückfrage, wenn für den Auftrag eine Angabe fehlt oder eine Wahl nötig ist. "
                + "Die Frage unterbricht die Antwort; nach der Antwort geht es weiter. Nur einmal je Frage, nicht für Bestätigungen.",
                SCHEMA, ToolRisk.READ, true, "ask-v1", true));
    }

    @Override public String invoke(ToolDefinition tool, JsonNode arguments) {
        throw new ToolNotStartedException("Rückfragen werden von der Person beantwortet, nicht ausgeführt.");
    }

    /** What the card shows and what an answer is checked against. */
    public record Question(String text, List<String> options, boolean freeText) { }

    static Question question(JsonNode args) {
        if (args == null || !args.isObject() || !args.path("question").isString()) return null;
        List<String> options = new ArrayList<>();
        for (JsonNode option : args.path("options")) if (option.isString()) options.add(option.asString());
        boolean free = options.isEmpty() || args.path("allowFreeText").asBoolean(false);
        return new Question(args.path("question").asString(), List.copyOf(options), free);
    }

    /** The cleaned answer, or {@code null} if it does not fit the stored question. */
    static String validAnswer(Question question, String raw) {
        if (question == null || raw == null) return null;
        String answer = raw.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
        if (answer.isEmpty() || answer.length() > MAX_ANSWER) return null;
        if (question.options().contains(answer)) return answer;
        return question.freeText() ? answer : null;
    }
}
