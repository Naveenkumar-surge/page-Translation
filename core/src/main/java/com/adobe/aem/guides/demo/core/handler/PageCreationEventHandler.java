package com.adobe.aem.guides.demo.core.handler;

import java.util.Collections;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.workflow.WorkflowSession;
import com.day.cq.replication.ReplicationAction;
import com.day.cq.replication.ReplicationActionType;

@Component(immediate = true, service = EventHandler.class, property = {
        "event.topics=" + ReplicationAction.EVENT_TOPIC
})
public class PageCreationEventHandler implements EventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PageCreationEventHandler.class);

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    private static final String WORKFLOW_MODEL_PATH = "/var/workflow/models/workflow";

    @Override
    public void handleEvent(Event event) {
         ReplicationAction action = ReplicationAction.fromEvent(event);

        if (action != null && ReplicationActionType.ACTIVATE.equals(action.getType())) {
            String path = action.getPath();
            LOG.info("Page Published: {}", path);
        if (path == null || path.isEmpty()) {
            LOG.warn("Received event with null or empty path");
            return;
        }

        LOG.info("Page creation event received for path: {}", path);

            try (ResourceResolver resolver = getServiceResourceResolver()) {
                Resource resource = resolver.getResource(path);
                if (resource != null) {
                    LOG.info("Page exists at path: {}", path);
                    initiateWorkflow(resource, resolver);
                } else {
                    LOG.warn("No resource found at path: {}", path);
                }
            } catch (Exception e) {
                LOG.error("Error handling event for path: {}", path, e);
            }
    }
    }

    private void initiateWorkflow(Resource resource, ResourceResolver resolver) {
        WorkflowSession workflowSession = resolver.adaptTo(WorkflowSession.class);
        if (workflowSession != null) {
            try {
                workflowSession.startWorkflow(
                        workflowSession.getModel(WORKFLOW_MODEL_PATH),
                        workflowSession.newWorkflowData("JCR_PATH", resource.getPath()));
                LOG.info("Workflow started for path: {}", resource.getPath());
            } catch (Exception e) {
                LOG.error("Failed to start workflow for path: {}", resource.getPath(), e);
            }
        }
    }

    private ResourceResolver getServiceResourceResolver() throws LoginException {
        return resourceResolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, "naveenkumar"));
    }
}