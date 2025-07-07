package com.adobe.aem.guides.demo.core.servlets;

// import com.adobe.granite.translation.api.TranslationObject;
// import com.adobe.granite.translation.api.TranslationProvider;
// import com.adobe.granite.translation.api.TranslationProject;
// import org.osgi.service.component.annotations.Component;
// import org.osgi.service.component.annotations.Reference;

// import java.util.Collection;
// import java.util.List;
// import java.util.stream.Collectors;
// import java.util.ArrayList;

// @Component(
//     service = TranslationProvider.class,
//     immediate = true,
//     property = {
//         TranslationProvider.PN_TRANSLATION_PROVIDER_NAME + "=custom-provider"
//     }
// )
public class CustomTranslationProvider  {

//     @Reference(target = "(translation.provider.name=existing-provider-name)")
//     private TranslationProvider delegate;

//     @Override
//     public String getName() {
//         return "custom-provider";
//     }

//     @Override
//     public String getDisplayName() {
//         return "Custom Provider with Extra Dropdown";
//     }

//     @Override
//     public Collection<String> getTranslationMethods() {
//         List<String> originalMethods = new ArrayList<>(delegate.getTranslationMethods());
//         originalMethods.add("new-dropdown-option"); // Add your dropdown field here
//         return originalMethods;
//     }

//     // Delegate rest of the methods to existing provider
//     @Override
//     public boolean supportsTranslationObject(TranslationObject object) {
//         return delegate.supportsTranslationObject(object);
//     }

//     @Override
//     public void updateTranslationObject(TranslationObject object, TranslationProject project) {
//         delegate.updateTranslationObject(object, project);
//     }

//     @Override
//     public void translate(Collection<TranslationObject> objects, TranslationProject project) {
//         delegate.translate(objects, project);
//     }

//     @Override
//     public void cancelTranslation(Collection<TranslationObject> objects, TranslationProject project) {
//         delegate.cancelTranslation(objects, project);
//     }

//     @Override
//     public void deleteTranslation(Collection<TranslationObject> objects, TranslationProject project) {
//         delegate.deleteTranslation(objects, project);
//     }
}
