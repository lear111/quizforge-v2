package cloud.treenewbee.quizforge.practice.resources;

import java.util.ArrayDeque;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import cloud.treenewbee.quizforge.rules.RuleRunnerException;

/** Host metadata binds screenshots to this exact submitted answer, independent of extension grading. */
public final class SubmissionImages {
    private SubmissionImages() {}
    public static String freeze(JsonMapper json,JsonNode projected,JsonNode answer) {
        var screenshots=json.createArrayNode();var pending=new ArrayDeque<JsonNode>();
        if(answer!=null)pending.add(answer);
        while(!pending.isEmpty()){
            var value=pending.removeLast();
            if(value.isObject()&&"qf-answer-area".equals(value.path("type").asString())&&value.has("snapshot")){
                var item=screenshots.addObject();item.put("areaId",value.path("id").asString());
                if(value.has("targetId"))item.set("targetId",value.get("targetId"));
                item.put("assetId",value.path("snapshot").path("attrs").path("assetId").asString());
            }else if(value.isObject()||value.isArray())for(var child:value)pending.add(child);
        }
        if(screenshots.isEmpty())return json.writeValueAsString(projected);
        if(!(projected instanceof ObjectNode))throw new RuleRunnerException("带答题截图的题型投影必须为对象");
        var frozen=(ObjectNode)projected.deepCopy();var metadata=json.createObjectNode();metadata.put("snapshotVersion",1);metadata.set("screenshots",screenshots);
        frozen.set("_qfSubmission",metadata);
        return json.writeValueAsString(frozen);
    }
}
