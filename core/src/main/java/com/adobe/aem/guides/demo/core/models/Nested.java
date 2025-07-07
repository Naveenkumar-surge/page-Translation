package com.adobe.aem.guides.demo.core.models;

import java.util.List;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
@Model(adaptables = Resource.class, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Nested {
    @ValueMapValue
    private String additionaltext;

    @ValueMapValue
    private String pathfield;

    @ValueMapValue
    private String checkbox;

    @ValueMapValue
    private String options;

    public String getAdditionaltext() {
        return additionaltext;
    }

    public String getPathfield() {
        return pathfield;
    }

    public String getCheckbox() {
        return checkbox;
    }

    public String getOptions() {
        return options;
    }
}
