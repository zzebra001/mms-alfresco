package gov.nasa.jpl.view_repo.actions.migrations;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gov.nasa.jpl.view_repo.db.ElasticHelper;
import gov.nasa.jpl.view_repo.db.PostgresHelper;
import gov.nasa.jpl.view_repo.util.JsonUtil;
import gov.nasa.jpl.view_repo.util.Sjm;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import org.alfresco.service.ServiceRegistry;
import org.apache.log4j.Logger;

public class Migrate_3_4_0 {

    static Logger logger = Logger.getLogger(Migrate_3_4_0.class);
    private static final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static JsonParser parser = new JsonParser();

    private static final String transientSettings = "{\"transient\": {\"script.max_compilations_per_minute\":120}}";

    private static final String searchQuery = "{\"query\": {\"type\" : {\"value\": \"ref\"}}}";

    private static final String updateQuery = "UPDATE \"commits\" SET timestamp = ? WHERE elasticid = ?";

    public static boolean apply(ServiceRegistry services) throws Exception {
        logger.info("Running Migrate_3_4_0");
        PostgresHelper pgh = new PostgresHelper();
        ElasticHelper eh = new ElasticHelper();

        // Temporarily increase max_compilations_per_minute
        eh.updateClusterSettings(transientSettings);

        boolean noErrors = true;

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        InputStream resourceAsStream = classLoader.getResourceAsStream("mapping_template.json");
        Scanner s = new Scanner(resourceAsStream).useDelimiter("\\A");
        if (s.hasNext()) {
            JsonObject mappingTemplate = JsonUtil.buildFromString(s.next());
            eh.applyTemplate(mappingTemplate.toString());
        }

        List<Map<String, String>> orgs = pgh.getOrganizations(null);

        for (Map<String, String> org : orgs) {
            String orgId = org.get("orgId");
            List<Map<String, Object>> projects = pgh.getProjects(orgId);
            for (Map<String, Object> project : projects) {
                String projectId = project.get(Sjm.SYSMLID).toString();
                pgh.setProject(projectId);

                JsonObject refs = eh.search(parser.parse(searchQuery).getAsJsonObject());
                Set<String> refsToMove = new HashSet<>();
                JsonArray payload = new JsonArray();
                if (refs.has("elements")) {
                    for (JsonElement ref : refs.get("elements").getAsJsonArray()) {
                        if (ref.getAsJsonObject().get(Sjm.ELASTICID) != null) {
                            refsToMove.add(ref.getAsJsonObject().get(Sjm.ELASTICID).getAsString());
                            payload.add(ref.getAsJsonObject());
                        }
                    }

                    if (!refsToMove.isEmpty()) {
                        eh.bulkDeleteByType(refsToMove, projectId, ElasticHelper.ELEMENT);
                        if (eh.refreshIndex()) {
                            eh.bulkIndexElements(payload, "added", true, projectId, ElasticHelper.REF);
                        }
                    }
                }

                List<Map<String, String>> commits = pgh.getAllCommits();
                for (Map<String, String> commit : commits) {
                    String commitId = commit.get("commitId");
                    if (!commitId.isEmpty()) {
                        JsonObject commitObject = eh.getByElasticId(commitId, projectId, ElasticHelper.COMMIT);
                        if (commitObject.has(Sjm.CREATED)) {
                            try (PreparedStatement statement = pgh.prepareStatement(updateQuery)) {
                                Date created = df.parse(commitObject.get(Sjm.CREATED).getAsString());
                                Timestamp ts = new Timestamp(created.getTime());
                                statement.setTimestamp(1, ts);
                                statement.setString(2, commitId);
                                statement.execute();
                            } catch (ParseException pe) {
                                logger.info("Unable to parse date: ", pe);
                            }
                        } else {
                            logger.error("Commit object has no created date.");
                            noErrors = false;
                        }
                    }
                }
            }
        }

        return noErrors;
    }
}
