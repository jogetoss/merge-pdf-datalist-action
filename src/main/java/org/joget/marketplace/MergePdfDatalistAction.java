package org.joget.marketplace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang.ArrayUtils;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppPluginUtil;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.datalist.model.DataList;
import org.joget.apps.datalist.model.DataListActionDefault;
import org.joget.apps.datalist.model.DataListActionResult;
import org.joget.apps.form.model.Element;
import org.joget.apps.form.model.Form;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.apps.form.service.FileUtil;
import org.joget.apps.form.service.FormUtil;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.StringUtil;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.util.WorkflowUtil;
import org.springframework.context.ApplicationContext;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import java.io.File;

public class MergePdfDatalistAction extends DataListActionDefault {

    private final static String MESSAGE_PATH = "messages/MergePdfDatalistAction";

    @Override
    public String getName() {
        return "Merge PDF Datalist Action";
    }

    @Override
    public String getVersion() {
        return Activator.VERSION;
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getLabel() {
        //support i18n
        return AppPluginUtil.getMessage("org.joget.marketplace.MergePdfDatalistAction.pluginLabel", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getDescription() {
        //support i18n
        return AppPluginUtil.getMessage("org.joget.marketplace.MergePdfDatalistAction.pluginDesc", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClassName(), "/properties/mergePdfDatalistAction.json", null, true, MESSAGE_PATH);
    }

    @Override
    public String getLinkLabel() {
        return getPropertyString("label"); //get label from configured properties options
    }

    @Override
    public String getHref() {
        return getPropertyString("href"); //Let system to handle to post to the same page
    }

    @Override
    public String getTarget() {
        return "post";
    }

    @Override
    public String getHrefParam() {
        return getPropertyString("hrefParam");  //Let system to set the parameter to the checkbox name
    }

    @Override
    public String getHrefColumn() {
        String recordIdColumn = getPropertyString("recordIdColumn"); //get column id from configured properties options
        if ("id".equalsIgnoreCase(recordIdColumn) || recordIdColumn.isEmpty()) {
            return getPropertyString("hrefColumn"); //Let system to set the primary key column of the binder
        } else {
            return recordIdColumn;
        }
    }

    @Override
    public String getConfirmation() {
        return getPropertyString("confirmation"); //get confirmation from configured properties options
    }

    @Override
    public DataListActionResult executeAction(DataList dataList, String[] rowKeys) {
        // only allow POST
        HttpServletRequest request = WorkflowUtil.getHttpServletRequest();
        if (request != null && !"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }

        // check for submited rows
        if (rowKeys != null && rowKeys.length > 0) {
            try {
                //get the HTTP Response
                HttpServletResponse response = WorkflowUtil.getHttpServletResponse();

                if (rowKeys.length == 1) {
                    //generate a pdf for download
                    singlePdf(request, response, rowKeys[0]);
                } else {
                    //generate a zip of all pdfs
                    multiplePdfs(request, response, rowKeys);
                }
            } catch (IOException | ServletException e) {
                LogUtil.error(getClassName(), e, "Fail to generate PDF for " + ArrayUtils.toString(rowKeys));
            }
        }

        //return null to do nothing
        return null;
    }

    public String getFileNameFromConfig(String id, String configFileName) {
        AppService appService = (AppService) FormUtil.getApplicationContext().getBean("appService");
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        String appVersion = String.valueOf(appDef.getVersion());
        String appId = appDef.getAppId();
        String formDefId = getPropertyString("formDefId");
        FormRowSet frs = appService.loadFormData(appId, appVersion, formDefId, id);
        FormRow formRow = frs.get(0);
        String fileName = (String) formRow.get(configFileName);
        return fileName;
    }

    /**
     * Handles for single pdf file
     *
     * @param request
     * @param response
     * @param rowKey
     * @throws IOException
     * @throws javax.servlet.ServletException
     */
    protected void singlePdf(HttpServletRequest request, HttpServletResponse response, String rowKey) throws IOException, ServletException {
        byte[] pdf = getPdf(rowKey);
        if (!getPropertyString("fileName").isEmpty()) {
            writeResponse(request, response, pdf, getFileNameFromConfig(rowKey, getPropertyString("fileName")) + ".pdf", "application/pdf");
        } else {
            writeResponse(request, response, pdf, rowKey + ".pdf", "application/pdf");
        }
    }

    /**
     * Handles for multiple files download. Put all pdfs in zip.
     *
     * @param request
     * @param response
     * @param rowKeys
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    protected void multiplePdfs(HttpServletRequest request, HttpServletResponse response, String[] rowKeys) throws IOException, ServletException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(baos);
        Map<String, Integer> fileNameCounts = new HashMap<>();

        try {
            //create pdf and put in zip
            for (String id : rowKeys) {
                byte[] pdf = getPdf(id);

                String fileName;
                if (!getPropertyString("fileName").isEmpty()) {
                    fileName = getFileNameFromConfig(id, getPropertyString("fileName")) + ".pdf";
                } else {
                    fileName = id + ".pdf";
                }

                // Check if the filename already exists in the zip
                if (fileNameCounts.containsKey(fileName)) {
                    int count = fileNameCounts.get(fileName);
                    count++;
                    fileNameCounts.put(fileName, count);

                    String baseFileName = fileName.substring(0, fileName.lastIndexOf('.'));
                    String fileExtension = fileName.substring(fileName.lastIndexOf('.'));

                    fileName = baseFileName + " (" + count + ")" + fileExtension;
                } else {
                    fileNameCounts.put(fileName, 0);
                }

                zip.putNextEntry(new ZipEntry(fileName));
                zip.write(pdf);
                zip.closeEntry();
            }

            zip.finish();
            if (!getPropertyString("zipFileName").isEmpty()) {
                writeResponse(request, response, baos.toByteArray(), getPropertyString("zipFileName") + ".zip", "application/zip");
            } else {
                writeResponse(request, response, baos.toByteArray(), getLinkLabel() + ".zip", "application/zip");
            }
        } finally {
            baos.close();
            zip.flush();
        }
    }

    /**
     * Generate PDF using FormPdfUtil
     *
     * @param id
     * @return
     */
    protected byte[] getPdf(String id) {
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        String formDefId = getPropertyString("formDefId");
        String fieldId = getPropertyString("fieldId");

        Boolean hideEmptyValueField = null;
        if (getPropertyString("hideEmptyValueField").equals("true")) {
            hideEmptyValueField = true;
        }

        Boolean showNotSelectedOptions = false;
        if (getPropertyString("showNotSelectedOptions").equals("true")) {
            showNotSelectedOptions = true;
        }
        Boolean repeatHeader = null;
        if ("true".equals(getPropertyString("repeatHeader"))) {
            repeatHeader = true;
        }
        Boolean repeatFooter = null;
        if ("true".equals(getPropertyString("repeatFooter"))) {
            repeatFooter = true;
        }
        String css = null;
        if (!getPropertyString("formatting").isEmpty()) {
            css = getPropertyString("formatting");
        }
        String header = null;
        if (!getPropertyString("headerHtml").isEmpty()) {
            header = getPropertyString("headerHtml");
            header = AppUtil.processHashVariable(header, null, null, null);
        }

        String footer = null;
        if (!getPropertyString("footerHtml").isEmpty()) {
            footer = getPropertyString("footerHtml");
            footer = AppUtil.processHashVariable(footer, null, null, null);
        }

        return createPdf(fieldId, formDefId, id, appDef, null, hideEmptyValueField, header, footer, css, showNotSelectedOptions, repeatHeader, repeatFooter);
    }

    public byte[] createPdf(String fieldId, String formId, String primaryKey, AppDefinition appDef, WorkflowAssignment assignment, Boolean hideEmpty, String header, String footer, String css, Boolean showAllSelectOptions, Boolean repeatHeader, Boolean repeatFooter) {
        try {
            ApplicationContext ac = AppUtil.getApplicationContext();
            AppService appService = (AppService) ac.getBean("appService");
            FormData formData = new FormData();
            formData.setPrimaryKeyValue(primaryKey);
            Form loadForm = appService.viewDataForm(appDef.getId(), appDef.getVersion().toString(), formId, null, null, null, formData, null, null);
            Element el = FormUtil.findElement(fieldId, loadForm, formData);
            File srcFile = FileUtil.getFile(FormUtil.getElementPropertyValue(el, formData), loadForm, primaryKey);
            String filePaths = srcFile.getPath();

            List<String> filePathList = getFilesList(filePaths);
            List<File> fileList = convertPathsToFiles(filePathList);

            return mergePdf(fileList, filePaths);

        } catch (Exception ex) {
            LogUtil.error(getClassName(), ex, ex.getMessage());
        }
        return null;

        // try {
        //     String html = getSelectedFormHtml(formId, primaryKey, appDef, assignment, hideEmpty);
        //     header = AppUtil.processHashVariable(header, assignment, null, null);
        //     footer = AppUtil.processHashVariable(footer, assignment, null, null);
        //     html = cleanFormHtml(html, showAllSelectOptions);
        //     return FormPdfUtil.createPdf(html, header, footer, css, showAllSelectOptions, repeatHeader, repeatFooter, true);
        // } catch (Exception e) {
        //     LogUtil.error(FormPdfUtil.class.getName(), e, "");
        // }
        // return null;
    }

    public List<String> getFilesList(String filePaths) {
        String[] fileArray = filePaths.split(";");
        List<String> fileList = new ArrayList<>();

        String directoryPath = "";
        for (String filePath : fileArray) {
            String fullPath = "";
            String trimmedPath = filePath.trim();
            int lastSeparatorIndex = trimmedPath.lastIndexOf(File.separator);
            if (lastSeparatorIndex != -1) {
                directoryPath = trimmedPath.substring(0, lastSeparatorIndex);
                String fileName = trimmedPath.substring(lastSeparatorIndex + 1);
                fullPath = directoryPath + File.separator + fileName;
            } else {
                fullPath = directoryPath + File.separator + trimmedPath;
            }
            fileList.add(fullPath);
        }
        return fileList;
    }

    public static List<File> convertPathsToFiles(List<String> filePaths) {
        List<File> fileList = new ArrayList<>();
        for (String filePath : filePaths) {
            fileList.add(new File(filePath));
        }
        return fileList;
    }

    public byte[] mergePdf(List<File> fileList, String filePaths) {

        try {
            // Path to the output merged PDF file
            String outputFilePath = "path/to/output/merged-file.pdf";

            // Create a PDFMergerUtility instance
            PDFMergerUtility pdfMerger = new PDFMergerUtility();

            // Set the destination file name
            pdfMerger.setDestinationFileName(filePaths + "merged.pdf");

            // Add all input PDF files to the merger utility
            for (File file : fileList) {
                pdfMerger.addSource(file);
            }

            // Create a ByteArrayOutputStream to hold the merged PDF
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                // Set the destination to the ByteArrayOutputStream
                pdfMerger.setDestinationStream(outputStream);

                // Merge the documents
                pdfMerger.mergeDocuments(null);

                // Return the merged PDF as a byte array
                return outputStream.toByteArray();
            }

            // Merge the documents
            // pdfMerger.mergeDocuments(null);
            // LogUtil.info(getClassName(), "PDF files merged successfully into " + outputFilePath);
        } catch (IOException e) {
            LogUtil.error(getClassName(), e, e.getMessage());
        }
        return null;
    }

    /**
     * Write to response for download
     *
     * @param request
     * @param response
     * @param bytes
     * @param filename
     * @param contentType
     * @throws IOException
     */
    protected void writeResponse(HttpServletRequest request, HttpServletResponse response, byte[] bytes, String filename, String contentType) throws IOException, ServletException {
        OutputStream out = response.getOutputStream();
        try {
            String name = URLEncoder.encode(filename, "UTF8").replaceAll("\\+", "%20");
            response.setHeader("Content-Disposition", "attachment; filename=" + name + "; filename*=UTF-8''" + name);
            response.setContentType(contentType + "; charset=UTF-8");

            if (bytes.length > 0) {
                response.setContentLength(bytes.length);
                out.write(bytes);
            }
        } finally {
            out.flush();
            out.close();

            //simply foward to a 
            request.getRequestDispatcher(filename).forward(request, response);
        }
    }

    public static String cleanFormHtml(String html, Boolean showAllSelectOptions) {

        //remove script
        html = html.replaceAll("(?s)<script[^>]*>.*?</script>", "");

        //remove style
        html = html.replaceAll("(?s)<style[^>]*>.*?</style>", "");

        //remove hidden field
        html = html.replaceAll("<input[^>]*type=\"hidden\"[^>]*>", "");
        html = html.replaceAll("<input[^>]*type=\'hidden\'[^>]*>", "");

        //remove <br>
        html = html.replaceAll("<br>", "<br/>");

        //remove form tag
        html = html.replaceAll("<form[^>]*>", "");
        html = html.replaceAll("</\\s?form>", "");

        //remove button
        html = html.replaceAll("<button[^>]*>[^>]*</\\s?button>>", "");

        //remove validator decorator
        html = html.replaceAll("<span\\s?class=\"[^\"]*cell-validator[^\"]?\"[^>]*>[^>]*</\\s?span>", "");

        //remove link
        html = html.replaceAll("<link[^>]*>", "");

        //remove id
        html = html.replaceAll("id=\"([^\\\"]*)\"", "");

        //remove hidden td
        html = html.replaceAll("<td\\s?style=\\\"[^\\\"]*display:none;[^\\\"]?\\\"[^>]*>.*?</\\s?td>", "");

        //convert label for checkbox and radio
        Pattern formdiv = Pattern.compile("<div class=\"form-cell-value\" >.*?</div>", Pattern.DOTALL);
        Matcher divMatcher = formdiv.matcher(html);
        while (divMatcher.find()) {
            String divString = divMatcher.group(0);

            Pattern tempPatternLabel = Pattern.compile("<label(.*?)>(.|\\s)*?</label>");
            Matcher tempMatcherLabel = tempPatternLabel.matcher(divString);
            int count = 0;
            String inputStringLabel = "";
            String replaceLabel = "";
            while (tempMatcherLabel.find()) {

                inputStringLabel = tempMatcherLabel.group(0);
                //get the input field
                Pattern patternInput = Pattern.compile("<input[^>]*>");
                Matcher matcherInput = patternInput.matcher(inputStringLabel);
                String tempLabel = "";
                if (matcherInput.find()) {
                    tempLabel = matcherInput.group(0);
                }

                //get the type
                Pattern patternType = Pattern.compile("type=\"([^\\\"]*)\"");
                Matcher matcherType = patternType.matcher(tempLabel);
                String type = "";
                if (matcherType.find()) {
                    type = matcherType.group(1);
                }

                if (type.equalsIgnoreCase("checkbox") || type.equalsIgnoreCase("radio")) {
                    if (showAllSelectOptions != null && showAllSelectOptions) {
                        replaceLabel += inputStringLabel.replaceAll("<label(.*?)>", "");
                        replaceLabel = replaceLabel.replaceAll("</label(.*?)>", "");
                    } else {
                        if (inputStringLabel.contains("checked")) {
                            if (count > 0) {
                                replaceLabel += ", ";
                            }
                            String label = "";
                            Pattern patternLabel = Pattern.compile("</i>(.|\\s)*?</label>");
                            Matcher matcherLabel = patternLabel.matcher(inputStringLabel);
                            if (matcherLabel.find()) {
                                label = matcherLabel.group(0);
                                label = label.replaceAll("<(.*?)i>", "");
                                label = label.replaceAll("</label(.*?)>", "");
                                label = label.trim();
                            }
                            replaceLabel += label;
                            count += 1;
                        }
                    }
                } else {
                    if (count > 0) {
                        replaceLabel += ", ";
                    }
                    String span = "";
                    Pattern patternSpan = Pattern.compile("<span(.*?)>(.|\\s)*?</span>");
                    Matcher matcherSpan = patternSpan.matcher(inputStringLabel);
                    if (matcherSpan.find()) {
                        span = matcherSpan.group(0);
                        span = span.replaceAll("<(.*?)span>", "");
                        span = span.replaceAll("</span(.*?)>", "");
                        span = span.trim();
                    }
                    replaceLabel += span;
                    count += 1;
                }
            }
            if (count > 0) {
                replaceLabel = "<span>" + replaceLabel + "</span>";
            }
            html = html.replaceAll(StringUtil.escapeRegex(divString), StringUtil.escapeRegex(replaceLabel));
        }
        return html;
    }
}
