package com.adobe.aem.guides.demo.core.servlets;
import com.day.cq.dam.api.AssetManager;
import org.apache.commons.io.IOUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

@Component(service = Servlet.class, property = {
        Constants.SERVICE_DESCRIPTION + "=Upload Dynamic API Data to DAM",
        "sling.servlet.paths=" + "/bin/upload/universal",
        "sling.servlet.methods=GET"
})
public class UniversalApiToDamServlet extends SlingAllMethodsServlet {

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException {

        String apiUrl = "http://localhost:3000/health-data"; // Replace with any dynamic API
        String damBasePath = "/content/dam/dynamic-api/";

        ResourceResolver resolver = request.getResourceResolver();
        AssetManager assetManager = resolver.adaptTo(AssetManager.class);

        if (assetManager == null) {
            throw new ServletException("AssetManager is null");
        }

        try {
            // Fetch API
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            String jsonResponse = IOUtils.toString(conn.getInputStream(), "UTF-8");

            JSONArray jsonArray = new JSONArray(jsonResponse);

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                String folderPath = damBasePath + "entry-" + (i + 1) + "/";

                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = obj.get(key);
                    String fileName = key;

                    if (value instanceof String) {
                        String strVal = (String) value;
                        if (strVal.matches("^(http|https)://.*\\.(jpg|jpeg|png|gif)$")) {
                            // Image file
                            InputStream imgStream = new URL(strVal).openStream();
                            assetManager.createAsset(folderPath + fileName + ".jpg", imgStream, "image/jpeg", true);
                        } else {
                            // Plain text
                            InputStream textStream = new ByteArrayInputStream(strVal.getBytes());
                            assetManager.createAsset(folderPath + fileName + ".txt", textStream, "text/plain", true);
                        }

                    } else if (value instanceof JSONObject || value instanceof JSONArray) {
                        // JSON Object or Array
                        InputStream jsonStream = new ByteArrayInputStream(value.toString().getBytes());
                        assetManager.createAsset(folderPath + fileName + ".json", jsonStream, "application/json", true);

                    } else {
                        // Treat other types (int, double, etc.) as text
                        InputStream textStream = new ByteArrayInputStream(value.toString().getBytes());
                        assetManager.createAsset(folderPath + fileName + ".txt", textStream, "text/plain", true);
                    }
                }
            }

            resolver.commit();
            response.getWriter().write("Dynamic API data uploaded to DAM successfully.");

        } catch (Exception e) {
            throw new ServletException("Error processing API data", e);
        }
    }
}
