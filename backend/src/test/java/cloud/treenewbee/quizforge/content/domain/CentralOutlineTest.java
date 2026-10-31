package cloud.treenewbee.quizforge.content.domain;

import static cloud.treenewbee.quizforge.content.domain.PackageFixture.*;
import static cloud.treenewbee.quizforge.content.domain.QbankPackageParserTest.*;
import static cloud.treenewbee.quizforge.content.domain.PackageRejectedException.Code.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CentralOutlineTest {
    static Object groups(Object item) { return List.of(Map.of("items", List.of(item))); }
    static Arguments bad(String name, Object value, String location) { return Arguments.of(name, value, location); }
    static Stream<Arguments> invalidOutlines() {
        List<Arguments> cases = new ArrayList<>(List.of(
                bad("null", null, "outline"), bad("object", Map.of(), "outline"), bad("empty", List.of(), "outline"),
                bad("too many groups", Collections.nCopies(10001, Map.of("items", List.of(Map.of("question", "q1")))), "outline"),
                bad("group not object", List.of("group"), "outline[0]"),
                bad("missing items", List.of(Map.of("group", "组")), "outline[0].items"),
                bad("empty items", List.of(Map.of("items", List.of())), "outline[0].items"),
                bad("items not array", List.of(Map.of("items", "q1")), "outline[0].items"),
                bad("too many items", List.of(Map.of("items", Collections.nCopies(10001, Map.of("question", "q1")))), "outline[0].items"),
                bad("extra group field", List.of(Map.of("items", List.of(Map.of("question", "q1")), "extra", true)), "outline[0]"),
                bad("item not object", groups("q1"), "outline[0].items[0]"),
                bad("missing question", groups(Map.of("label", "1")), "question"),
                bad("non string question", groups(Map.of("question", 1)), "question"),
                bad("unknown question", groups(Map.of("question", "missing")), "question"),
                bad("case mismatch", groups(Map.of("question", "Q1")), "question"),
                bad("extra item field", groups(Map.of("question", "q1", "extra", true)), "outline[0].items[0]"),
                bad("label and parts", groups(Map.of("question", "q1", "label", "1", "parts", List.of(Map.of("id", "a", "label", "1")))), "outline[0].items[0]"),
                bad("duplicate within group", List.of(Map.of("items", List.of(Map.of("question", "q1"), Map.of("question", "q1")))), "outline[0].items[1]"),
                bad("duplicate across groups", List.of(Map.of("items", List.of(Map.of("question", "q1"))), Map.of("items", List.of(Map.of("question", "q1")))), "outline[1].items[0]")));
        for (Object value : Arrays.asList(null, 1, "", " ", "x".repeat(81), " x", "x ", "\u00a0x", "<x>", "x\u0000", "x\u0085")) {
            Map<String,Object> group = new LinkedHashMap<>(); group.put("group", value); group.put("items", List.of(Map.of("question", "q1")));
            cases.add(bad("group plain " + value, List.of(group), "outline[0]"));
            Map<String,Object> item = new LinkedHashMap<>(); item.put("question", "q1"); item.put("label", value);
            cases.add(bad("label plain " + value, groups(item), "outline[0].items[0]"));
        }
        return cases.stream();
    }
    @ParameterizedTest(name="{0}") @MethodSource("invalidOutlines")
    void rejectsEveryInvalidGroupAndReferenceWithLocation(String name, Object outline, String location) throws Exception {
        var f=fixture(); f.bank(b -> b.put("outline", outline));
        assertThat(rejected(f, INVALID_QUESTION_BANK).getMessage()).contains("练习", location);
    }
    @Test void missingRootOutlineAndLegacyPerQuestionOutlineAreRejected() throws Exception {
        var missing=fixture(); missing.bank(b -> b.remove("outline"));
        assertThat(rejected(missing, INVALID_QUESTION_BANK).getMessage()).contains("outline");
        var old=fixture(); old.question(q -> q.put("outline", Map.of("level", "question", "label", "1")));
        assertThat(rejected(old, INVALID_QUESTION_BANK).getMessage()).contains("q1", "outline");
    }
    static Stream<Arguments> invalidParts() {
        List<Arguments> cases=new ArrayList<>();
        for(Object value: Arrays.asList(null, "x", List.of(), Collections.nCopies(101, Map.of("id","a","label","1")),
                List.of("x"), List.of(Map.of("id","a")), List.of(Map.of("label","1")),
                List.of(Map.of("id","a","label","1","extra",true)),
                List.of(Map.of("id","a","label","1"),Map.of("id","a","label","2")))) cases.add(Arguments.of(value));
        for(String field:List.of("id","label")) for(Object value:Arrays.asList(null, 1, "", " ", " x", "x ", "<x>", "x\u0000", "x\u0085", "x".repeat(field.equals("id")?129:81))) {
            Map<String,Object> part=new LinkedHashMap<>(Map.of("id","a","label","1")); part.put(field,value); cases.add(Arguments.of(List.of(part)));
        }
        return cases.stream();
    }
    @ParameterizedTest @MethodSource("invalidParts")
    void partsEnforceShapePlainTextUniqueIdsAndBounds(Object parts) throws Exception {
        var f=fixture(); capability(f);
        Map<String,Object> item=new LinkedHashMap<>();item.put("question","q1");item.put("parts",parts);
        f.bank(b -> b.put("outline",groups(item)));
        assertThat(rejected(f,INVALID_QUESTION_BANK).getMessage()).contains("outline[0].items[0].parts");
    }
    @Test void partsRequireDeclaredCapabilityAndAcceptExactLimits() throws Exception {
        var f=fixture(); List<Object> parts=new ArrayList<>();
        for(int i=0;i<100;i++)parts.add(Map.of("id","子".repeat(126)+String.format("%02d",i),"label","标".repeat(80)));
        f.bank(b->b.put("outline",groups(Map.of("question","q1","parts",parts))));
        rejected(f,INVALID_QUESTION_BANK);capability(f);assertThat(f.parse().banks()).hasSize(1);
    }
    @Test void groupsAndItemsAcceptTenThousandAndCaseSensitiveStableIds() throws Exception {
        var f=fixture();List<Object> questions=new ArrayList<>(),items=new ArrayList<>(),groups=new ArrayList<>();
        for(int i=0;i<10000;i++) {String id="q"+i;questions.add(Map.of("id",id,"title","题","data",Map.of()));var item=Map.of("question",id);items.add(item);groups.add(Map.of("items",List.of(item)));}
        f.bank(b->{b.put("questions",questions);b.put("outline",groups);});assertThat(f.parse().banks()).hasSize(1);
        f.bank(b->b.put("outline",List.of(Map.of("group","组".repeat(80),"items",items))));assertThat(f.parse().banks()).hasSize(1);
    }
    @Test void omittedLabelsPartsAndGroupsAreValidAndAuthorValuesRemainUnchanged() throws Exception {
        var f=fixture();f.bank(b->b.put("outline",groups(Map.of("question","q1","label","标".repeat(80)))));
        assertThat(f.parse().banks().getFirst().bank().get("outline")).isEqualTo(groups(Map.of("question","q1","label","标".repeat(80))));
        f.bank(b->b.put("outline",groups(Map.of("question","q1"))));assertThat(f.parse().banks()).hasSize(1);
    }
    @Test void unreferencedQuestionStillGetsAllStructureChecks() throws Exception {
        for(Consumer<Map<String,Object>> change: List.<Consumer<Map<String,Object>>>of(q->q.remove("data"),q->q.put("title",""),q->q.put("extension",Map.of("path","extensions/unknown/missing")),q->q.put("outline",Map.of()))) {
            var f=fixture();f.bank(b->{var q=new LinkedHashMap<>(object(((List<?>)b.get("questions")).getFirst()));q.put("id","unused");change.accept(q);b.put("questions",List.of(((List<?>)b.get("questions")).getFirst(),q));});
            assertThat(rejected(f,INVALID_QUESTION_BANK).getMessage()).contains("unused");
        }
    }
    @Test void extensionExamplesAlsoRequireCentralOutline() throws Exception {
        var f=fixture();f.edit(f.extension+"/examples/bank.json",b->b.remove("outline"));
        assertThat(rejected(f,INVALID_EXTENSION).getMessage()).contains("outline");
    }
    @Test void partIdsAreScopedToTheirQuestionAndCaseSensitive() throws Exception {
        var f=fixture();capability(f);
        f.bank(b->{
            var q=new LinkedHashMap<>(object(((List<?>)b.get("questions")).getFirst()));q.put("id","Q1");
            b.put("questions",List.of(((List<?>)b.get("questions")).getFirst(),q));
            var parts=List.of(Map.of("id","a","label","1"),Map.of("id","A","label","2"));
            b.put("outline",List.of(Map.of("items",List.of(Map.of("question","q1","parts",parts),Map.of("question","Q1","parts",parts)))));
        });
        assertThat(f.parse().banks()).hasSize(1);
    }

    private static void capability(PackageFixture f) {f.manifest(m->m.put("requiresApi",Map.of("major",1,"minMinor",1,"capabilities",List.of("outline-items"))));}
}
