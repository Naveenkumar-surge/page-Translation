package com.adobe.aem.guides.demo.core.slingjob;

import com.adobe.aem.guides.demo.core.AppConstant.importclass;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.sling.api.resource.*;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Component(service = JobConsumer.class, property = JobConsumer.PROPERTY_TOPICS + importclass.pagePublishApi)
public class PageApiJobConsumer implements JobConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(PageApiJobConsumer.class);
    private static final String SUBSERVICE = importclass.SUBSERVICE;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    public JobResult process(Job job) {
        try {
            String pagePaths = (String) job.getProperty(importclass.pages);
            LOG.info("first {}", pagePaths);
            if (pagePaths == null || pagePaths.isEmpty()) {
                LOG.warn("No page data received in job.");
                return JobResult.FAILED;
            }
            ObjectMapper mapper = new ObjectMapper();
            String responseBody;
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpPost request = new HttpPost(importclass.API);
                request.setHeader("Content-Type", "application/json");
                request.setEntity(new StringEntity(pagePaths, StandardCharsets.UTF_8));
                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int statusCode = response.getStatusLine().getStatusCode();
                    responseBody = EntityUtils.toString(response.getEntity());

                    LOG.info("API Response Status: {}", statusCode);
                    LOG.info("API Response Body:\n{}", responseBody);

                    if (statusCode < 200 || statusCode >= 300) {
                        LOG.error("API call failed with status: {} and body: {}", statusCode, responseBody);
                        return JobResult.FAILED;
                    }
                }
            }
            Map<String, Object> json = mapper.readValue(responseBody, Map.class);
            List<Map<String, Object>> pages = (List<Map<String, Object>>) json.get(importclass.pages);

            if (pages == null) {
                LOG.warn("No 'pages' key found in JSON.");
                return JobResult.FAILED;
            }

            try (ResourceResolver resolver = getServiceResourceResolver()) {
                for (Map<String, Object> pageJson : pages) {
                    LOG.info("pages {}", pageJson);
                    applyPageJson(resolver, pageJson);
                }
                resolver.commit();
            }

            return JobResult.OK;

        } catch (Exception e) {
            LOG.error(" Error processing pagePublishApiJob:", e);
            return JobResult.FAILED;
        }
    }

    private ResourceResolver getServiceResourceResolver() throws LoginException {
        Map<String, Object> authMap = new HashMap<>();
        authMap.put(ResourceResolverFactory.SUBSERVICE, SUBSERVICE);
        return resolverFactory.getServiceResourceResolver(authMap);
    }

    private void applyPageJson(ResourceResolver resolver, Map<String, Object> pageJson) {
        String pagePath = (String) pageJson.get(importclass.path);
        if (pagePath == null) {
            LOG.warn("Page path is missing in JSON.");
            return;
        }

        Resource contentResource = resolver.getResource(pagePath + importclass.jcr_content);
        if (contentResource == null) {
            LOG.info("jcr:content not found for path: {}", pagePath);
            return;
        }

        Map<String, Object> pageProps = (Map<String, Object>) pageJson.get(importclass.properties);
        if (pageProps != null) {
            updateProperties(contentResource, pageProps);
        }

        List<Map<String, Object>> components = (List<Map<String, Object>>) pageJson.get(importclass.components);
        if (components != null) {
            updateComponents(contentResource, components);
        }
    }

    private void updateComponents(Resource parent, List<Map<String, Object>> componentsJson) {
        Map<String, List<Resource>> groupedResources = new HashMap<>();
        Map<String, List<Map<String, Object>>> groupedJson = new HashMap<>();
        collectComponentsRecursively(parent, groupedResources);
        for (Map<String, Object> jsonComp : componentsJson) {
            String compName = (String) jsonComp.get(importclass.componentName);
            if (compName != null) {
                groupedJson.computeIfAbsent(compName, k -> new ArrayList<>()).add(jsonComp);
            }
        }
        for (String compName : groupedJson.keySet()) {
            List<Resource> resources = groupedResources.getOrDefault(compName, Collections.emptyList());
            List<Map<String, Object>> jsonList = groupedJson.getOrDefault(compName, Collections.emptyList());

            int size = Math.min(resources.size(), jsonList.size());
            for (int i = 0; i < size; i++) {
                Resource matched = resources.get(i);
                Map<String, Object> compJson = jsonList.get(i);
                LOG.info("Updating component '{}' at {}", compName, matched.getPath());
                updateComponentProperties(matched, compJson);
            }

            if (resources.size() != jsonList.size()) {
                LOG.warn("Component count mismatch for '{}': JSON={}, JCR={}", compName, jsonList.size(),
                        resources.size());
            }
        }
    }

    private void updateComponentProperties(Resource matched, Map<String, Object> componentJson) {
        Map<String, Object> propsToUpdate = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> multifieldMap = new HashMap<>();

        for (Map.Entry<String, Object> entry : componentJson.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (importclass.componentName.equals(key))
                continue;

            if (value instanceof List && ((List<?>) value).size() > 0 && ((List<?>) value).get(0) instanceof Map) {
                multifieldMap.put(key, (List<Map<String, Object>>) value);
            } else {
                propsToUpdate.put(key, value);
            }
        }
        updateProperties(matched, propsToUpdate);
        ResourceResolver resolver = matched.getResourceResolver();
        for (Map.Entry<String, List<Map<String, Object>>> multifieldEntry : multifieldMap.entrySet()) {
            String fieldName = multifieldEntry.getKey();
            List<Map<String, Object>> subItems = multifieldEntry.getValue();
            Resource multifieldContainer = matched.getChild(fieldName);
            if (multifieldContainer != null) {
                int j = 0;
                for (Resource item : multifieldContainer.getChildren()) {
                    if (j >= subItems.size())
                        break;

                    updateProperties(item, subItems.get(j));
                    j++;
                }

                if (j < subItems.size()) {
                    LOG.warn("Not all multifield items were updated for '{}'. JSON had {}, JCR had {}",
                            fieldName, subItems.size(), j);
                }

            } else {
                if (!subItems.isEmpty()) {
                    Map<String, Object> firstItem = subItems.get(0);
                    Object pathObj = firstItem.get("path");

                    if (pathObj instanceof String) {
                        String path = (String) pathObj;

                        if (!path.startsWith("/content") || path.matches(".*\\.(jpg|png|PDF|JPG|PNG|Pdf)$")) {
                            LOG.warn("Skipping invalid path '{}' for field '{}'", path, fieldName);
                            return;
                        }

                        Resource baseResource = resolver.getResource(path);
                        if (baseResource == null) {
                            LOG.warn("Could not resolve path '{}' for field '{}'", path, fieldName);
                            return; 
                        }

                        Resource jcrContent = baseResource.getChild("jcr:content");
                        if (jcrContent == null) {
                            LOG.warn("Missing jcr:content under '{}'", path);
                            return;
                        }

                        LOG.info("Resolved base resource for multifield fallback '{}' at {}", fieldName,
                                jcrContent.getPath());

                        for (Map<String, Object> subItem : subItems) {
                            for (Map.Entry<String, Object> entry : subItem.entrySet()) {
                                String key = entry.getKey();
                                if ("path".equals(key))
                                    continue;

                                Object value = entry.getValue();
                                boolean updated = false;

                                if (path.startsWith("/content/dam")) {
                                    updated = updatePropertyIfExists(jcrContent, key, value);

                                    if (!updated) {
                                        Resource master = jcrContent.getChild("data/master");
                                        if (master != null) {
                                            updated = updatePropertyIfExists(master, key, value);
                                        }
                                    }
                                } else {
                                    updated = updatePropertyIfExists(jcrContent, key, value);
                                    if (!updated) {
                                        Resource root = jcrContent.getChild("root");
                                        if (root != null) {
                                            updated = updatePropertyRecursively(root, key, value);
                                        }
                                    }
                                }

                                if (updated) {
                                    LOG.info(" Successfully updated '{}' for field '{}' in path '{}'", key, fieldName,
                                            path);
                                } else {
                                    LOG.warn(" Could not update '{}' for field '{}' in path '{}'", key, fieldName,
                                            path);
                                }
                            }
                        }

                    } else {
                        LOG.warn("No valid 'path' key found in first item for field '{}'", fieldName);
                    }
                }
            }

        }
    }

    private void collectComponentsRecursively(Resource resource, Map<String, List<Resource>> grouped) {
        if (resource == null)
            return;

        for (Resource component : resource.getChildren()) {
            String resourceType = component.getResourceType();
            String componentName = (resourceType != null && resourceType.contains(importclass.Slash))
                    ? resourceType.substring(resourceType.lastIndexOf(importclass.Slash) + 1)
                    : component.getName();
            LOG.info("🔍 Found component: '{}' at path: {}", componentName, component.getPath());
            grouped.computeIfAbsent(componentName, k -> new ArrayList<>()).add(component);
            collectComponentsRecursively(component, grouped);
        }
    }

    private boolean updatePropertyIfExists(Resource resource, String key, Object value) {
        ModifiableValueMap map = resource.adaptTo(ModifiableValueMap.class);
        if (map != null && map.containsKey(key)) {
            if (value instanceof Collection) {
                map.put(key, ((Collection<?>) value).toArray(new String[0]));
            } else {
                map.put(key, value);
            }
            return true;
        }
        return false;
    }

    private boolean updatePropertyRecursively(Resource parent, String key, Object value) {
        if (updatePropertyIfExists(parent, key, value)) {
            return true;
        }
        for (Resource child : parent.getChildren()) {
            if (updatePropertyRecursively(child, key, value)) {
                return true;
            }
        }
        return false;
    }

    private void updateProperties(Resource resource, Map<String, Object> newProps) {
        ModifiableValueMap modMap = resource.adaptTo(ModifiableValueMap.class);
        if (modMap == null) {
            LOG.warn("Cannot adapt resource to ModifiableValueMap: {}", resource.getPath());
            return;
        }
        for (Map.Entry<String, Object> entry : newProps.entrySet()) {
            String key = entry.getKey();
            Object newValue = entry.getValue();
            if ("path".equals(key))
                continue;
            if (modMap.containsKey(key)) {
                if (newValue instanceof Collection) {
                    modMap.put(key, ((Collection<?>) newValue).toArray(new String[0]));
                } else if (newValue instanceof Object[]) {
                    modMap.put(key, newValue);
                } else {
                    modMap.put(key, newValue);
                }

                LOG.info("Updated property '{}' = '{}' on {}", key, newValue, resource.getPath());
            } else {
                LOG.warn("Property '{}' does not exist on '{}', skipping update.", key, resource.getPath());
            }
        }

    }
}
