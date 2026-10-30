package cn.diaojiahong.myyoutube.common.media;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public record ReviewVerdict(String decision, double riskScore, double confidence, String reason,
                            JsonArray evidence, String model, String rulesVersion) {
    public ReviewVerdict(
            String decision,
            double riskScore,
            double confidence,
            String reason,
            JsonArray evidence,
            String model) {
        this(decision, riskScore, confidence, reason, evidence, model, null);
    }
    public boolean passes(double maxRisk, double minConfidence) {
        return "PASS".equals(decision) && riskScore <= maxRisk && confidence >= minConfidence;
    }
    public static ReviewVerdict parse(String response, String model) {
        return parse(response, model, null);
    }
    public static ReviewVerdict parse(String response, String model, String rulesVersion) {
        if (rulesVersion != null && !rulesVersion.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("审核模板版本非法");
        JsonObject json = StructuredJson.strictObject(response);
        if (!string(json, "decision") || !number(json, "riskScore") || !number(json, "confidence")
                || !string(json, "reason") || !json.has("evidence") || !json.get("evidence").isJsonArray())
            throw new IllegalArgumentException("审核输出结构非法");
        String decision = json.get("decision").getAsString();
        double risk = json.get("riskScore").getAsDouble(), confidence = json.get("confidence").getAsDouble();
        String reason = json.get("reason").getAsString();
        if (!java.util.List.of("PASS", "REJECT", "UNCERTAIN").contains(decision) || !Double.isFinite(risk)
                || risk < 0 || risk > 1 || !Double.isFinite(confidence) || confidence < 0 || confidence > 1
                || reason.isBlank()
                || reason.length() > 4000
                || !json.has("evidence")
                || !json.get("evidence").isJsonArray())
            throw new IllegalArgumentException("审核输出不合法");
        return new ReviewVerdict(
                decision,
                risk,
                confidence,
                reason,
                json.getAsJsonArray("evidence"),
                model,
                rulesVersion);
    }
    private static boolean string(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isString();
    }
    private static boolean number(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isNumber();
    }
}
