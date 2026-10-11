package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.media.StructuredJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public record ReviewVerdict(String decision, double riskScore, double confidence, String reason,
                            JsonArray evidence, String model) {
    public boolean passes(double maxRisk, double minConfidence) {
        return "PASS".equals(decision) && riskScore <= maxRisk && confidence >= minConfidence;
    }
    public static ReviewVerdict parse(String response, String model) {
        JsonObject json = StructuredJson.object(response);
        String decision = json.get("decision").getAsString();
        double risk = json.get("riskScore").getAsDouble(), confidence = json.get("confidence").getAsDouble();
        String reason = json.get("reason").getAsString();
        if (!java.util.List.of("PASS", "REJECT", "UNCERTAIN").contains(decision) || !Double.isFinite(risk)
                || risk < 0 || risk > 1 || !Double.isFinite(confidence) || confidence < 0 || confidence > 1
                || reason.isBlank() || reason.length() > 4000 || !json.has("evidence") || !json.get("evidence").isJsonArray())
            throw new IllegalArgumentException("审核输出不合法");
        return new ReviewVerdict(decision, risk, confidence, reason, json.getAsJsonArray("evidence"), model);
    }
}
