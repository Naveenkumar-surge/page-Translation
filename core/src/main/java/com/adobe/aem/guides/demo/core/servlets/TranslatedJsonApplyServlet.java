package com.adobe.aem.guides.demo.core.servlets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.IOUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.*;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.methods=POST",
                "sling.servlet.paths=/bin/applyTranslatedJson"
        }
)
@MultipartConfig
public class TranslatedJsonApplyServlet extends SlingAllMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(TranslatedJsonApplyServlet.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    private static final String SUBSERVICE = "naveenkumar";

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {

        if (!ServletFileUpload.isMultipartContent(request)) {
            response.setStatus(400);
            response.getWriter().write("Request must be multipart/form-data");
            return;
        }

        Part filePart = request.getPart("file");
        if (filePart == null) {
            response.setStatus(400);
            response.getWriter().write("File part is missing");
            return;
        }

        try (InputStream inputStream = filePart.getInputStream()) {
            byte[] bytes = IOUtils.toByteArray(inputStream);
            String jsonString = new String(bytes, StandardCharsets.UTF_8);

            if (jsonString.startsWith("\uFEFF")) {
                jsonString = jsonString.substring(1); // Remove BOM
            }

            LOG.info("✅ Raw JSON input: {}", jsonString);

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> json = mapper.readValue(jsonString, Map.class);
            List<Map<String, Object>> pages = (List<Map<String, Object>>) json.get("pages");

            if (pages == null) {
                LOG.warn("No 'pages' key found in JSON.");
                response.setStatus(400);
                response.getWriter().write("JSON must contain 'pages' key.");
                return;
            }

            try (ResourceResolver resolver = getServiceResourceResolver()) {
                for (Map<String, Object> pageJson : pages) {
                    applyPageJson(resolver, pageJson);
                }

                resolver.commit();
                response.setStatus(200);
                response.getWriter().write("Translated data applied successfully");
            } catch (Exception e) {
                LOG.error("❌ Error applying translated data", e);
                response.setStatus(500);
                response.getWriter().write("Server error: " + e.getMessage());
            }

        } catch (Exception e) {
            LOG.error("❌ Error processing uploaded file or JSON", e);
            response.setStatus(500);
            response.getWriter().write("Invalid JSON or server error");
        }
    }

    private ResourceResolver getServiceResourceResolver() throws LoginException {
        Map<String, Object> authMap = new HashMap<>();
        authMap.put(ResourceResolverFactory.SUBSERVICE, SUBSERVICE);
        return resolverFactory.getServiceResourceResolver(authMap);
    }

    private void applyPageJson(ResourceResolver resolver, Map<String, Object> pageJson) {
        String pagePath = (String) pageJson.get("path");
        if (pagePath == null) {
            LOG.warn("Page path is missing in JSON.");
            return;
        }

        Resource contentResource = resolver.getResource(pagePath + "/jcr:content");
        if (contentResource == null) {
            LOG.info("jcr:content not found for path: {}", pagePath);
            return;
        }

        Map<String, Object> pageProps = (Map<String, Object>) pageJson.get("properties");
        if (pageProps != null) {
            updateProperties(contentResource, pageProps);
        }

        List<Map<String, Object>> components = (List<Map<String, Object>>) pageJson.get("components");
        if (components != null) {
            updateComponents(contentResource, components);
        }
    }

    private void updateComponents(Resource parent, List<Map<String, Object>> componentsJson) {
        Map<String, List<Resource>> groupedResources = new HashMap<>();
        Map<String, List<Map<String, Object>>> groupedJson = new HashMap<>();

        // Step 1: Group JCR resources by component name
        collectComponentsRecursively(parent, groupedResources);

        // Step 2: Group JSON objects by componentName
        for (Map<String, Object> jsonComp : componentsJson) {
            String compName = (String) jsonComp.get("componentName");
            if (compName != null) {
                groupedJson.computeIfAbsent(compName, k -> new ArrayList<>()).add(jsonComp);
            }
        }

        // Step 3: Match and update by index
        for (String compName : groupedJson.keySet()) {
            List<Resource> resources = groupedResources.getOrDefault(compName, Collections.emptyList());
            List<Map<String, Object>> jsonList = groupedJson.getOrDefault(compName, Collections.emptyList());

            int size = Math.min(resources.size(), jsonList.size());
            for (int i = 0; i < size; i++) {
                Resource matched = resources.get(i);
                Map<String, Object> compJson = jsonList.get(i);
                LOG.info("✅ Updating component '{}' at {}", compName, matched.getPath());
                updateComponentProperties(matched, compJson);
            }

            if (resources.size() != jsonList.size()) {
                LOG.warn("⚠️ Component count mismatch for '{}': JSON={}, JCR={}", compName, jsonList.size(), resources.size());
            }
        }
    }

    private void updateComponentProperties(Resource matched, Map<String, Object> componentJson) {
        // Separate properties and multifields for the update
        Map<String, Object> propsToUpdate = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> multifieldMap = new HashMap<>();

        for (Map.Entry<String, Object> entry : componentJson.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if ("componentName".equals(key)) continue;

            if (value instanceof List && ((List<?>) value).size() > 0 && ((List<?>) value).get(0) instanceof Map) {
                multifieldMap.put(key, (List<Map<String, Object>>) value);
            } else {
                propsToUpdate.put(key, value);
            }
        }

        // Update simple properties (only update if they exist)
        updateProperties(matched, propsToUpdate);

        // Update multifields
        for (Map.Entry<String, List<Map<String, Object>>> multifieldEntry : multifieldMap.entrySet()) {
            String fieldName = multifieldEntry.getKey();
            List<Map<String, Object>> subItems = multifieldEntry.getValue();

            Resource multifieldContainer = matched.getChild(fieldName);
            if (multifieldContainer != null) {
                int j = 0;
                for (Resource item : multifieldContainer.getChildren()) {
                    if (j >= subItems.size()) break;
                    updateProperties(item, subItems.get(j));
                    j++;
                }
                if (j < subItems.size()) {
                    LOG.warn("⚠️ Not all multifield items were updated for '{}'. JSON had {}, JCR had {}",
                            fieldName, subItems.size(), j);
                }
            } else {
                LOG.warn("⚠️ Could not find multifield container '{}' under '{}'", fieldName, matched.getPath());
            }
        }
    }

    private void collectComponentsRecursively(Resource resource, Map<String, List<Resource>> grouped) {
        for (Resource child : resource.getChildren()) {
            if (child.getValueMap().containsKey("sling:resourceType")) {
                String type = child.getResourceType();
                if (type != null) {
                    String componentName = type.substring(type.lastIndexOf("/") + 1);
                    grouped.computeIfAbsent(componentName, k -> new ArrayList<>()).add(child);
                }
            }
            collectComponentsRecursively(child, grouped);
        }
    }

    private void updateProperties(Resource resource, Map<String, Object> newProps) {
        ModifiableValueMap modMap = resource.adaptTo(ModifiableValueMap.class);
        if (modMap == null) {
            LOG.warn("Cannot adapt resource to ModifiableValueMap: {}", resource.getPath());
            return;
        }

        // Update properties if they exist, instead of setting new ones
        for (Map.Entry<String, Object> entry : newProps.entrySet()) {
            String key = entry.getKey();
            Object newValue = entry.getValue();

            if (modMap.containsKey(key)) {
                modMap.put(key, newValue); // Update the property
                LOG.info("✅ Updated property '{}' = '{}' on {}", key, newValue, resource.getPath());
            } else {
                LOG.warn("⚠️ Property '{}' does not exist on '{}', skipping update.", key, resource.getPath());
            }
        }
    }
}
