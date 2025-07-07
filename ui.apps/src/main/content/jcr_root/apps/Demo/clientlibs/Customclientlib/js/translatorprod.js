console.log("NEW CLIENTLIBS");
(function(document, $) {
  "use strict";

  $(document).on("click", "a.translation-pod-action.cq-translation-pod-action-start", function(e) {
    e.preventDefault();

    console.log("[Translation Override] Start button clicked");

    var $btn = $(this);
    var translationJobPath = $btn.attr("data-translationjobpath");
    var targetLanguage = $btn.attr("data-targetlanguage");

    console.log("[Translation Override] translationJobPath:", translationJobPath);
    console.log("[Translation Override] targetLanguage:", targetLanguage);

    var trackingDataRaw = $btn.attr("data-foundation-tracking-event");
    var pages = [];

    try {
      var trackingData = JSON.parse(trackingDataRaw);
      if (trackingData && trackingData["translationPages"]) {
          console.log(trackingData["translationPages"]);
        var pageCount = parseInt(trackingData["translationPages"]);
        for (var i = 1; i <= pageCount; i++) {
          pages.push(translationJobPath);
        }
      }
    } catch (err) {
      console.warn("[Translation Override] Failed to parse tracking data:", err);
    }

    console.log("[Translation Override] Final Pages Array:", pages);

    // Build query params
    var queryParams = new URLSearchParams();
    queryParams.append("target_language", targetLanguage);
    pages.forEach(function(pagePath) {
      queryParams.append("pages", pagePath); // appends multiple "pages" entries
    });
    console.log(queryParams.toString());

    var fullUrl = "/bin/translation/startParkerTranslation?" + queryParams.toString();

    // Make GET request
    $.ajax({
      url: fullUrl,
      type: "GET",
      success: function(response) {
        console.log("[Translation Override] Translation triggered:", response);
        alert("Translation triggered!");
      },
      error: function(xhr, status, error) {
        console.error("[Translation Override] AJAX Error:", status, error);
        console.error("Response Text:", xhr.responseText);
        alert("Error triggering translation.");
      }
    });
  });

})(document, jQuery);