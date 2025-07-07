package com.adobe.aem.guides.demo.core.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.adobe.aem.guides.demo.core.AppConstant.importclass;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.*;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import javax.jcr.ValueFormatException;
import javax.jcr.version.VersionException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

@Component(service = Servlet.class, property = {
        "sling.servlet.methods=GET",
        "sling.servlet.paths=/bin/translation/startParkerTranslation"
})
public class AiTranslationWorkflowStep extends SlingAllMethodsServlet {
    private static String targetLanguag;
    private static String Target;
    private static final Logger LOG = LoggerFactory.getLogger(AiTranslationWorkflowStep.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private JobManager jobManager;

    private static final Set<String> EXCLUDED_PROPS = new HashSet<>(Arrays.asList(
            "jcr:lastModified", "jcr:lastModifiedBy", "jcr:createdBy", "jcr:created", "pages",
            "jcr:primaryType", "layout", "cq:lastReplicationAction",
            "cq:lastReplicatedBy", "cq:lastReplicated", "jcr:baseVersion",
            "cq:lastModified", "cq:lastModifiedBy", "sling:resourceType", "variationName", "paragraphScope", "cq:name",
            "cq:xfVariantType",
            "lastFragmentSave", "cq:parentPath", "cq:template", "jcr:mixinTypes",
            "jcr:isCheckedOut", "jcr:predecessors", "jcr:versionHistory", "jcr:uuid", "cq:tags", "cq:styleIds", "type",
            "hTags", "navTitle", "pageTitle", "contentGroup", "cq:translationProject", "cq:contextHubSegmentsPath",
            "cq:lastTranslationUpdate"));

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {

        ResourceResolver resolver = null;
        targetLanguag = request.getParameter(importclass.taget_language);
        String[] parentPaths = request.getParameterValues(importclass.pages);
        Target = targetLanguag;
        if (Target.contains("-")) {
            Target = Target.replace("-", "");
        }
        if (targetLanguag == null || parentPaths == null) {
            response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("Missing required parameters: targetLanguage pages");
            return;
        }

        List<Page> sourcePaths = new ArrayList<>();

        try {
            resolver = getServiceResourceResolver();
            for (String parentPath : parentPaths) {
                Resource parentResource = resolver.getResource(parentPath);

                if (parentResource == null) {
                    LOG.error("PageManager could not be adapted");
                    response.setStatus(500);
                    response.getWriter().write("Error: PageManager unavailable.");
                    return;
                }
                if (parentPaths == null || parentPaths.length == 0) {
                    LOG.warn("No parent paths provided.");
                    response.setStatus(400);
                    response.getWriter().write("Error: No parent paths provided.");
                    return;
                }
                Resource childPagesNode = parentResource.getChild(importclass.child_pages);
                if (childPagesNode == null) {
                    LOG.warn("No 'child_pages' node found under: {}", parentPath);
                    continue;
                }
                for (Resource child : childPagesNode.getChildren()) {
                    ValueMap vm = child.getValueMap();
                    if (vm.containsKey(importclass.sourcePath)) {
                        String source = vm.get(importclass.sourcePath, String.class);
                        String sourcePath = excludePrefixUntilSecondContent(source);
                        LOG.info("sourcePath {}",sourcePath);
                        if (sourcePath != null && !sourcePath.isEmpty()) {
                            PageManager pageManager = resolver.adaptTo(PageManager.class);
                            if (pageManager != null) {
                                Page sourcePage = pageManager.getPage(sourcePath);
                                if (sourcePage != null) {
                                    sourcePaths.add(sourcePage);
                                } else {
                                    LOG.warn("Page not found at sourcePath: {}", sourcePath);
                                }
                            }
                        }
                    }
                }
            }

            LOG.info("Collected {} source paths for target language: {}", sourcePaths.size(), targetLanguag);
            sendPagesInBatches(sourcePaths);
        } catch (Exception e) {
            LOG.error("Error in servlet execution", e);
            response.setStatus(500);
            response.getWriter().write("Error: " + e.getMessage());
        } finally {
            if (resolver != null && resolver.isLive()) {
                resolver.close();
            }
        }
    }

    public String excludePrefixUntilSecondContent(String sourcePath) {
        String[] parts = sourcePath.split("/");
        int contentCount = 0;
        int secondContentIndex = -1;
        for (int i = 0; i < parts.length; i++) {
            if ("content".equals(parts[i])) {
                contentCount++;
                if (contentCount == 2) {
                    secondContentIndex = i;
                    break;
                }
            }
        }
        if (contentCount >= 2 && secondContentIndex != -1) {
            return "/" + String.join("/", Arrays.copyOfRange(parts, secondContentIndex, parts.length));
        }
        return sourcePath;
    }

    private ResourceResolver getServiceResourceResolver() throws LoginException {
        Map<String, Object> authMap = new HashMap<>();
        authMap.put(ResourceResolverFactory.SUBSERVICE, importclass.SUBSERVICE);
        return resolverFactory.getServiceResourceResolver(authMap);
    }

    private void sendPagesInBatches(List<Page> pages) {
        List<Map<String, Object>> currentBatch = new ArrayList<>();
        int count = 0;

        for (Page page : pages) {
            Map<String, Object> pageJson = new LinkedHashMap<>();
            pageJson.put(importclass.path, page.getPath());

            Map<String, Object> pageProperties = new LinkedHashMap<>();
            ValueMap valueMap = page.getProperties();
            Object titleValue = valueMap.get(importclass.jcr_title);

            if (titleValue != null && !(titleValue.getClass().isArray() || titleValue instanceof List)) {
                pageProperties.put(importclass.jcr_title, titleValue);
            }

            pageJson.put(importclass.properties, pageProperties);

            List<Map<String, Object>> components = collectAllComponents(page.getContentResource());
            if (!components.isEmpty()) {
                pageJson.put(importclass.components, components);
            }

            currentBatch.add(pageJson);
            count++;

            if (shouldDispatchBatch(currentBatch, count, pages.size())) {
                dispatchJob(new ArrayList<>(currentBatch));
                currentBatch.clear();
            }
        }
    }

    private boolean shouldDispatchBatch(List<Map<String, Object>> batch, int count, int total) {
        return batch.size() >= getDynamicBatchSize() || count == total;
    }

    private int getDynamicBatchSize() {
        return 500;
    }

    private void dispatchJob(List<Map<String, Object>> batchData) {
        Map<String, Object> jobPayload = new HashMap<>();

        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);

            List<Map<String, Object>> cleanedPages = new ArrayList<>();
            for (Map<String, Object> page : batchData) {
                cleanedPages.add(cleanForJson(page));
            }

            String json = mapper.writeValueAsString(Collections.singletonMap(importclass.pages, cleanedPages));
            LOG.info("Dispatched batch with {} pages:\n{}", batchData.size(), json);
            JsonNode translationDataNode = mapper.readTree(json);
            ObjectNode root = mapper.createObjectNode();
            root.put(importclass.taget_language, targetLanguag);
            root.set(importclass.excluded_keys, mapper.valueToTree(Arrays.asList(
                    "path", "fileReference", "cardCtaLink", "thumbnailImageURL", "imageBoxCtaLink", "componentName")));
            root.put(importclass.translationdata, translationDataNode);
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            String finalPayload = mapper.writeValueAsString(root);
            LOG.info("Final Payload: {}", finalPayload);
            jobPayload.put(importclass.pages, finalPayload);

            jobManager.addJob(importclass.pagePublishApiJob, jobPayload);
        } catch (Exception e) {
            LOG.error("Error logging JSON batch payload", e);
        }
    }

    private List<Map<String, Object>> collectAllComponents(Resource resource) {
        List<Map<String, Object>> componentsList = new ArrayList<>();
        if (resource == null)
            return componentsList;

        for (Resource component : resource.getChildren()) {
            Map<String, Object> componentData = new LinkedHashMap<>();
            Map<String, Object> contentData = new LinkedHashMap<>();
            String resourceType = component.getResourceType();
            String componentName = (resourceType != null && resourceType.contains(importclass.Slash))
                    ? resourceType.substring(resourceType.lastIndexOf(importclass.Slash) + 1)
                    : component.getName();

            componentData.put(importclass.componentName, componentName);

            for (Map.Entry<String, Object> entry : component.getValueMap().entrySet()) {
                if (!EXCLUDED_PROPS.contains(entry.getKey()) && !isExcludedValue(entry.getValue())) {
                    Object value = entry.getValue();
                    if (value instanceof String && ((String) value).startsWith("/content")) {
                        String stringValue = (String) value;
                        String ChangingPathValue = stringValue;
                        String lowerCaseValue = stringValue.toLowerCase();

                        if (lowerCaseValue.endsWith(".jpg") || lowerCaseValue.endsWith(".png")
                                || lowerCaseValue.endsWith(".pdf")) {
                            componentData.put(entry.getKey(), stringValue);
                        } else {
                            String updatedPath = replaceLastLanguageOccurrenceWithTargetLanguage(stringValue);

                            String exist = replaceLastLanguageOccurrenceWith(stringValue);
                            if (!exist.equals("exist") && (updatedPath.equals(ChangingPathValue))) {
                                ChangingPathValue = ChangingPathValue + "-en";
                                updatedPath = replaceLastLanguageOccurrenceWithTargetLanguage(ChangingPathValue);
                            }
                            LOG.info("updatedPath {}", updatedPath);
                            ModifiableValueMap modMap = component.adaptTo(ModifiableValueMap.class);
                            if (modMap != null) {
                                modMap.put(entry.getKey(), updatedPath);
                            }
                            Session session = resource.getResourceResolver().adaptTo(Session.class);
                            if (session != null && session.isLive()) {
                                try {
                                    session.save();
                                } catch (RepositoryException e) {
                                    LOG.error("Session save failed", e);
                                }
                            }

                            boolean hasFolder = CreateFolderPath(stringValue, updatedPath);
                            if (!hasFolder) {
                                List<Map<String, Object>> childNodeProps = getChildNodeProperties(updatedPath,
                                        resource);
                                if (!childNodeProps.isEmpty()) {
                                    contentData.put(entry.getKey(), childNodeProps);
                                }
                            }
                        }
                    } else {
                        componentData.put(entry.getKey(),
                                (value instanceof Object[]) ? Arrays.asList((Object[]) value) : value);
                    }
                }
            }

            Map<String, List<Map<String, Object>>> multifieldMap = new HashMap<>();
            for (Resource child : component.getChildren()) {
                Map<String, Object> data = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : child.getValueMap().entrySet()) {
                    if (!EXCLUDED_PROPS.contains(entry.getKey()) && !isExcludedValue(entry.getValue())) {
                        Object value = entry.getValue();
                        if (value instanceof String && ((String) value).startsWith("/content")) {
                            String stringValue = (String) value;
                            String ChangingPathValue = stringValue;
                            String lowerCaseValue = stringValue.toLowerCase();

                            if (lowerCaseValue.endsWith(".jpg") || lowerCaseValue.endsWith(".png")
                                    || lowerCaseValue.endsWith(".pdf") || lowerCaseValue.endsWith(".html")) {
                                data.put(entry.getKey(), stringValue);
                            } else {
                                String updatedPath = replaceLastLanguageOccurrenceWithTargetLanguage(stringValue);
                                String exist = replaceLastLanguageOccurrenceWith(stringValue);
                                if (!exist.equals("exist") && (updatedPath.equals(ChangingPathValue))) {
                                    ChangingPathValue = ChangingPathValue + "-en";
                                    updatedPath = replaceLastLanguageOccurrenceWithTargetLanguage(ChangingPathValue);
                                }
                                LOG.info("updatedPath {}", updatedPath);
                                ModifiableValueMap modMap = child.adaptTo(ModifiableValueMap.class);
                                if (modMap != null) {
                                    modMap.put(entry.getKey(), updatedPath);
                                }
                                Session session = resource.getResourceResolver().adaptTo(Session.class);
                                if (session != null && session.isLive()) {
                                    try {
                                        session.save();
                                    } catch (RepositoryException e) {
                                        LOG.error("Session save failed", e);
                                    }
                                }
                                boolean HasFolder = CreateFolderPath(stringValue, updatedPath);
                                if (!HasFolder) {
                                    List<Map<String, Object>> childNodeProps = getChildNodeProperties(updatedPath,
                                            child);
                                    if (!childNodeProps.isEmpty()) {
                                        contentData.put(entry.getKey(), childNodeProps);
                                    }
                                }
                            }
                        } else {
                            data.put(entry.getKey(),
                                    (value instanceof Object[]) ? Arrays.asList((Object[]) value) : value);
                        }
                    }
                }
                if (!data.isEmpty()) {
                    multifieldMap.computeIfAbsent(component.getName(), k -> new ArrayList<>()).add(data);
                }
            }

            componentData.putAll(multifieldMap);
            componentData.putAll(contentData);
            componentsList.add(componentData);

            componentsList.addAll(collectAllComponents(component));
        }

        return componentsList;
    }

    private String replaceLastLanguageOccurrenceWith(String path) {
        List<String> languages = Arrays.asList(
                "cs", "da", "de", "en_us", "es", "fi", "fr", "hu", "it", "ja",
                "ko", "nl", "nb", "pl", "pt", "ru", "sv", "th", "tr", "vi", "zh", "zhtw", "tw", "en","ar");

        String[] parts = path.split("/");

        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i];

            String[] subParts = part.split("[-_]");
            for (int j = 0; j < subParts.length; j++) {
                if (languages.contains(subParts[j])) {
                    if ((subParts[j]).equals(Target))
                        return "exist";
                }
            }
        }

        return path;
    }

    private String replaceLastLanguageOccurrenceWithTargetLanguage(String path) {
        List<String> languages = Arrays.asList(
                "cs", "da", "de", "en_us", "es", "fi", "fr", "hu", "it", "ja",
                "ko", "nl", "nb", "pl", "pt", "ru", "sv", "th", "tr", "vi", "zh", "zhtw", "tw", "en","ar");

        String[] parts = path.split("/");

        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i];

            String[] subParts = part.split("[-_]");
            for (int j = 0; j < subParts.length; j++) {
                if (languages.contains(subParts[j])) {
                    subParts[j] = Target;

                    String newPart;
                    if (part.contains("-")) {
                        newPart = String.join("-", subParts);
                    } else if (part.contains("_")) {
                        newPart = String.join("_", subParts);
                    } else {
                        newPart = String.join("", subParts);
                    }

                    parts[i] = newPart;
                    return String.join("/", parts);
                }
            }
        }

        return path;
    }

    private boolean CreateFolderPath(String path, String TargetPath) {
        boolean hasFolder = true;
        if (path == null || !TargetPath.startsWith("/content")) {
            return false;
        }
        ResourceResolver resolver = null;
        try {
            resolver = getServiceResourceResolver();
            Resource baseResource = resolver.getResource(TargetPath);
            if (baseResource == null) {
                duplicateFolderAndContents(path, TargetPath);
                hasFolder = false;
            }
        } catch (LoginException e) {
            LOG.error("Failed to get service ResourceResolver for path: {}", path, e);
        } finally {
            if (resolver != null && resolver.isLive()) {
                resolver.close();
            }
        }
        return hasFolder;
    }

    private List<Map<String, Object>> getChildNodeProperties(String path, Resource resources) {
        List<Map<String, Object>> componentsList = new ArrayList<>();
        if (path == null || !path.startsWith("/content")) {
            return componentsList;
        }

        ResourceResolver resolver = null;
        try {
            resolver = getServiceResourceResolver();
            Resource baseResource = resolver.getResource(path);

            if (baseResource != null) {
                Resource jcrContent = baseResource.getChild("jcr:content");

                if (jcrContent != null) {
                    Map<String, Object> combinedProps = new LinkedHashMap<>();
                    combinedProps.put("path", path);
                    combinedProps.putAll(extractProperties(jcrContent));
                    if (path.startsWith("/content/dam")) {
                        Resource masterNode = jcrContent.getChild("data/master");
                        if (masterNode != null) {
                            combinedProps.putAll(extractProperties(masterNode));
                        }
                    } else {
                        Resource rootNode = jcrContent.getChild("root");
                        if (rootNode != null) {
                            collectAllChildProperties(rootNode, combinedProps);
                        }
                    }
                    if (!combinedProps.isEmpty()) {
                        componentsList.add(combinedProps);
                    }
                }
            }
        } catch (LoginException e) {
            LOG.error("Failed to get service ResourceResolver for path: {}", path, e);
        } finally {
            if (resolver != null && resolver.isLive()) {
                resolver.close();
            }
        }

        return componentsList;
    }

    private void duplicateFolderAndContents(String sourcePath, String targetPath) {
        if (sourcePath == null || targetPath == null || !sourcePath.startsWith("/content")
                || !targetPath.startsWith("/content")) {
            LOG.warn("Invalid source or target path. Source: {}, Target: {}", sourcePath, targetPath);
            return;
        }

        ResourceResolver resolver = null;
        try {
            resolver = getServiceResourceResolver();
            String[] sourceParts = sourcePath.split("/");
            String[] targetParts = targetPath.split("/");

            int diffIndex = -1;
            for (int i = 0; i < Math.min(sourceParts.length, targetParts.length); i++) {
                if (!sourceParts[i].equals(targetParts[i])) {
                    diffIndex = i;
                    break;
                }
            }

            if (diffIndex == -1) {
                LOG.warn("Source and target paths are the same. Nothing to clone.");
                return;
            }

            String commonBasePath = String.join("/", Arrays.copyOfRange(sourceParts, 0, diffIndex));
            Resource baseTargetResource = resolver.getResource(commonBasePath);

            if (baseTargetResource == null) {
                LOG.error("Base target parent resource not found: {}", commonBasePath);
                return;
            }

            Node currentTargetParentNode = baseTargetResource.adaptTo(Node.class);
            if (currentTargetParentNode == null) {
                LOG.error("Could not adapt base target resource to node.");
                return;
            }

            for (int i = diffIndex; i < targetParts.length; i++) {
                String targetNodeName = targetParts[i];
                String sourceNodePath = String.join("/", Arrays.copyOfRange(sourceParts, 0, i + 1));
                Resource sourceResource = resolver.getResource(sourceNodePath);
                if (sourceResource == null) {
                    LOG.warn("Corresponding source node not found for: {}", sourceNodePath);
                    return;
                }

                Node sourceNode = sourceResource.adaptTo(Node.class);
                if (sourceNode == null) {
                    LOG.warn("Could not adapt source resource to node: {}", sourceNodePath);
                    return;
                }

                if (!currentTargetParentNode.hasNode(targetNodeName)) {
                    currentTargetParentNode = currentTargetParentNode.addNode(targetNodeName,
                            sourceNode.getPrimaryNodeType().getName());
                    LOG.info("Created intermediate node: {}", currentTargetParentNode.getPath());
                } else {
                    currentTargetParentNode = currentTargetParentNode.getNode(targetNodeName);
                }
            }

            Session session = resolver.adaptTo(Session.class);
            Resource sourceRootResource = resolver.getResource(sourcePath);
            Node sourceRootNode = sourceRootResource != null ? sourceRootResource.adaptTo(Node.class) : null;

            if (sourceRootNode != null && currentTargetParentNode != null && session != null) {
                NodeIterator children = sourceRootNode.getNodes();
                while (children.hasNext()) {
                    Node child = children.nextNode();
                    cloneNode(child, currentTargetParentNode, child.getName());
                }

                session.save();
                LOG.info("Successfully cloned subtree from [{}] to [{}]", sourcePath, targetPath);
            } else {
                LOG.warn("Could not adapt source/target nodes or session is null.");
            }

        } catch (LoginException | RepositoryException e) {
            LOG.error("Error duplicating content from [{}] to [{}]", sourcePath, targetPath, e);
        } finally {
            if (resolver != null && resolver.isLive()) {
                resolver.close();
            }
        }
    }

    private void cloneNode(Node sourceNode, Node parentTargetNode, String targetNodeName) throws RepositoryException {
        if (sourceNode == null || parentTargetNode == null)
            return;

        Node newNode = parentTargetNode.addNode(targetNodeName, sourceNode.getPrimaryNodeType().getName());

        PropertyIterator properties = sourceNode.getProperties();
        while (properties.hasNext()) {
            Property prop = properties.nextProperty();
            if (!prop.getDefinition().isProtected()) {
                try {
                    if (prop.isMultiple()) {
                        newNode.setProperty(prop.getName(), prop.getValues());
                    } else {
                        newNode.setProperty(prop.getName(), prop.getValue());
                    }
                } catch (ValueFormatException | VersionException | ConstraintViolationException e) {
                    LOG.warn("Failed to set property [{}] on [{}]", prop.getName(), newNode.getPath(), e);
                }
            }
        }

        NodeIterator children = sourceNode.getNodes();
        while (children.hasNext()) {
            Node child = children.nextNode();
            cloneNode(child, newNode, child.getName());
        }
    }

    private Map<String, Object> extractProperties(Resource resource) {
        Map<String, Object> props = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : resource.getValueMap().entrySet()) {
            if (!EXCLUDED_PROPS.contains(entry.getKey()) && !isExcludedValue(entry.getValue())) {
                Object value = entry.getValue();
                props.put(entry.getKey(),
                        (value instanceof Object[]) ? Arrays.asList((Object[]) value) : value);
            }
        }
        return props;
    }

    private List<Map<String, Object>> collectAllChildProperties(Resource resource, Map<String, Object> combinedProps) {
        List<Map<String, Object>> list = new ArrayList<>();
        combinedProps.putAll(extractProperties(resource));
        Map<String, Object> props = extractProperties(resource);
        if (!props.isEmpty()) {
            list.add(props);
        }

        for (Resource child : resource.getChildren()) {
            collectAllChildProperties(child, combinedProps);
        }

        return list;
    }

    private boolean isExcludedValue(Object value) {
        if (value == null)
            return true;
        if (value instanceof String) {
            if (((String) value).startsWith("/content")) {
                return false;
            }
        }
        if (value instanceof Boolean)
            return true;
        if (value instanceof Object[]) {
            for (Object element : (Object[]) value) {
                if (isExcludedValue(element)) {
                    return true;
                }
            }
        }

        if (value instanceof Number)
            return true;

        if (value instanceof String) {
            String str = ((String) value).trim().toLowerCase();
            if (str.matches(importclass.Tags)) {
                return false;
            }
            if (importclass.True.equals(str) || importclass.False.equals(str))
                return true;
            if (str.matches(importclass.CharacterMatching) && str.matches(importclass.NumberMatching))
                return true;

            try {
                Double.parseDouble(str);
                return true;
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cleanForJson(Map<String, Object> original) {
        Map<String, Object> cleaned = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : original.entrySet()) {
            Object value = entry.getValue();

            if (value instanceof Map) {
                cleaned.put(entry.getKey(), cleanForJson((Map<String, Object>) value));
            } else if (value instanceof List) {
                cleaned.put(entry.getKey(), cleanList((List<?>) value));
            } else if (isJsonFriendly(value)) {
                cleaned.put(entry.getKey(), value);
            } else {
                cleaned.put(entry.getKey(), value != null ? value.toString() : null);
            }
        }

        return cleaned;
    }

    private List<Object> cleanList(List<?> list) {
        List<Object> cleanedList = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                cleanedList.add(cleanForJson((Map<String, Object>) item));
            } else if (item instanceof List) {
                cleanedList.add(cleanList((List<?>) item));
            } else if (isJsonFriendly(item)) {
                cleanedList.add(item);
            } else {
                cleanedList.add(item != null ? item.toString() : null);
            }
        }
        return cleanedList;
    }

    private boolean isJsonFriendly(Object value) {
        return value == null ||
                value instanceof String ||
                value instanceof Number ||
                value instanceof Boolean;
    }
}
