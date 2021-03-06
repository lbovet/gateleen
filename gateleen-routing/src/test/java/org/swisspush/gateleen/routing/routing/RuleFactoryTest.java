package org.swisspush.gateleen.routing.routing;

import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import io.vertx.core.buffer.Buffer;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for the RuleFactory class
 *
 * @author https://github.com/hofer [Marc Hofer]
 */
@RunWith(VertxUnitRunner.class)
public class RuleFactoryTest {

    private Map<String, Object> properties;


    @Before
    public void setUp(){
        properties = new HashMap<>();
        properties.put("gateleen.test.prop.valid", "http://someserver/");
    }

    @Test
    public void testSimpleRuleConfigParsing(TestContext context){
        String simpleExampleRule = "{"
                + "  \"/gateleen/rule/1\": {"
                + "    \"description\": \"Test Rule 1\","
                + "    \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\""
                + "  },"
                + "  \"/gateleen/rule/2\": {"
                + "    \"description\": \"Test Rule 2\","
                + "    \"url\": \"${gateleen.test.prop.2}/gateleen/rule/2\""
                + "  }"
                + "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");
        properties.put("gateleen.test.prop.2", "http://someserver2/");

        List<Rule> rules =  new RuleFactory(properties).parseRules(Buffer.buffer(simpleExampleRule));

        context.assertTrue(rules.size() == 2);
        context.assertEquals("someserver1", rules.get(0).getHost());
        context.assertEquals("someserver2", rules.get(1).getHost());
    }

    @Test
    public void testExpandOnBackendRule(TestContext context){
        String expandOnBackendRule = "{"
                + "  \"/gateleen/rule/1\": {"
                + "    \"description\": \"Test Rule 1\","
                + "    \"expandOnBackend\": true,"
                + "    \"url\": \"${gateleen.test.prop.1}/gateleen/rule/1\""
                + "  }"
                + "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");

        List<Rule> rules =  new RuleFactory(properties).parseRules(Buffer.buffer(expandOnBackendRule));

        context.assertTrue(rules.size() == 1);
        context.assertEquals(true, rules.get(0).isExpandOnBackend());
    }

    @Test
    public void testStorageExpandRule(TestContext context){
        String storageExpandRule = "{" +
                " \"/gateleen/rule/1\": {" +
                "  \"description\": \"Test Rule 1\"," +
                "  \"path\": \"/${gateleen.test.prop.1}/gateleen/rule/1\"," +
                "  \"storageExpand\": true," +
                "  \"storage\": \"main\"" +
                " }," +
                " \"/gateleen/rule/2\": {" +
                "  \"description\": \"Test Rule 2\"," +
                "  \"path\": \"/${gateleen.test.prop.1}/gateleen/rule/2\"," +
                "  \"storageExpand\": false," +
                "  \"storage\": \"main\"" +
                " }," +
                " \"/gateleen/rule/3\": {" +
                "  \"description\": \"Test Rule 3\"," +
                "  \"path\": \"/${gateleen.test.prop.1}/gateleen/rule/3\"," +
                "  \"storage\": \"main\"" +
                " }" +
                "}";

        properties.put("gateleen.test.prop.1", "http://someserver1/");

        List<Rule> rules =  new RuleFactory(properties).parseRules(Buffer.buffer(storageExpandRule));

        context.assertEquals(3, rules.size());
        context.assertTrue(rules.get(0).isStorageExpand(), "Rule has property 'storageExpand' with value true. So isStorageExpand() should return true");
        context.assertFalse(rules.get(1).isStorageExpand(), "Rule has property 'storageExpand' with value false. So isStorageExpand() should return false");
        context.assertFalse(rules.get(2).isStorageExpand(), "Rule has no property 'storageExpand'. So isStorageExpand() should return false");
    }
}