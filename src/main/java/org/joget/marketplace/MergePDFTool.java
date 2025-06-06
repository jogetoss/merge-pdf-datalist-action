package org.joget.marketplace;

import org.apache.commons.io.FileUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.Element;
import org.joget.apps.form.model.Form;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.apps.form.service.FileUtil;
import org.joget.apps.form.service.FormUtil;
import org.joget.commons.util.LogUtil;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.workflow.model.WorkflowAssignment;
import org.springframework.context.ApplicationContext;
import org.apache.tika.Tika;

import java.io.*;
import java.util.*;

public class MergePDFTool extends DefaultApplicationPlugin {

    private static final String MESSAGE_PATH = "messages/MergePdfTool";

    @Override
    public Object execute(Map map) {

        // Basic plugin context setup
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        ApplicationContext ac = AppUtil.getApplicationContext();
        AppService appService = (AppService) ac.getBean("appService");

        // Read plugin property config from JSON:
        String sourceFormDefId = getPropertyString("formDefId");
        String outputFormDefId = getPropertyString("formDefIdOutputFile");
        String outputFileFieldId = getPropertyString("outputFileFieldId");

        // Read the list of PDF fields from the "fields" grid
        Object[] fieldsArray = (Object[]) map.get("fields");
        List<String> fieldIdList = new ArrayList<>();
        if (fieldsArray != null && fieldsArray.length > 0) {
            for (Object rowObj : fieldsArray) {
                if (rowObj instanceof Map) {
                    Map<?, ?> rowMap = (Map<?, ?>) rowObj;
                    String fieldId = (String) rowMap.get("field");
                    if (fieldId != null && !fieldId.isEmpty()) {
                        fieldIdList.add(fieldId);
                    }
                }
            }
        }

        String recordId;

        WorkflowAssignment wfAssignment = (WorkflowAssignment) map.get("workflowAssignment");
        if (wfAssignment != null) {
            recordId = appService.getOriginProcessId(wfAssignment.getProcessId());
        } else {
            recordId = (String) properties.get("recordId");
        }
        // Check minimal configuration
        if (sourceFormDefId == null || sourceFormDefId.trim().isEmpty()) {
            LogUtil.warn(getClassName(), "Missing config: formDefId is not set. Exiting plugin.");
            return null;
        }
        if (outputFormDefId == null || outputFileFieldId == null) {
            LogUtil.warn(getClassName(), "Missing config: output form or output field is not set. Exiting plugin.");
            return null;
        }

        try {
            // 1) Load the source form for the record
            FormData formData = new FormData();
            formData.setPrimaryKeyValue(recordId);
            Form sourceForm = appService.viewDataForm(
                    appDef.getId(),
                    String.valueOf(appDef.getVersion()),
                    sourceFormDefId,
                    null, null, null,
                    formData,
                    null, null
            );
            if (sourceForm == null) {
                LogUtil.error(getClassName(), null, "Failed to load source form: " + sourceFormDefId);
                return null;
            }

            // 2) Collect all PDF paths from the specified fields
            List<String> allPdfPaths = collectAllPdfPaths(sourceForm, formData, fieldIdList);
            if (allPdfPaths.isEmpty()) {
                return null;
            }

            // 3) Convert string paths to actual File objects
            List<File> pdfFiles = convertPathsToFiles(allPdfPaths, sourceForm, recordId);
            if (pdfFiles.isEmpty()) {
                LogUtil.warn(getClassName(), "No valid PDF files to merge.");
                return null;
            }

            // 4) Merge the PDF files into a single PDF in memory
            byte[] mergedPdfBytes = mergePdfFiles(pdfFiles);
            if (mergedPdfBytes == null || mergedPdfBytes.length == 0) {
                LogUtil.error(getClassName(), null, "Merge returned empty or null PDF data.");
                return null;
            }

            // 5) Save the merged PDF file into the output form & field
            saveMergedPdf(mergedPdfBytes, outputFormDefId, outputFileFieldId, recordId, appDef, appService);

        } catch (Exception ex) {
            LogUtil.error(getClassName(), ex, "Error merging and saving PDFs in MergePDFTool.");
        }
        return null;
    }

    private List<String> collectAllPdfPaths(Form sourceForm, FormData formData, List<String> fieldIdList) {
        List<String> allPaths = new ArrayList<>();
        for (String fieldId : fieldIdList) {
            Element el = FormUtil.findElement(fieldId, sourceForm, formData);
            if (el != null) {
                String rawValue = FormUtil.getElementPropertyValue(el, formData);
                if (rawValue != null && !rawValue.trim().isEmpty()) {
                    String[] splitted = rawValue.split(";");
                    for (String path : splitted) {
                        if (path != null && !path.trim().isEmpty()) {
                            allPaths.add(path.trim());
                        }
                    }
                } else {
                }
            } else {
            }
        }
        return allPaths;
    }

    public boolean isPdfFile(File file) {
        try {
            Tika tika = new Tika();
            String mimeType = tika.detect(file);
            return "application/pdf".equals(mimeType);
        } catch (IOException e) {
            LogUtil.error(getClassName(), e, "Error detecting file type for: " + file.getAbsolutePath());
            return false;
        }
    }

    private List<File> convertPathsToFiles(List<String> paths, Form sourceForm, String recordId) {
        List<File> files = new ArrayList<>();

        for (String path : paths) {
            try {
                File file = FileUtil.getFile(path, sourceForm, recordId);
                if (file != null && file.exists()) {
                    if (isPdfFile(file)) { // Validate file type
                        files.add(file);
                    } else {
                        LogUtil.warn(getClassName(), "Invalid file type (not a PDF): " + file.getAbsolutePath());
                    }
                } else {
                    LogUtil.warn(getClassName(), "File not found or invalid: " + path);
                }
            } catch (IOException ex) {
                LogUtil.error(getClassName(), ex, "Error retrieving file: " + path);
            }
        }
        return files;
    }

    private byte[] mergePdfFiles(List<File> pdfFiles) {
        PDFMergerUtility merger = new PDFMergerUtility();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            for (File f : pdfFiles) {
                merger.addSource(f);
            }
            merger.setDestinationStream(baos);
            merger.mergeDocuments(null);
            return baos.toByteArray();
        } catch (IOException ex) {
            LogUtil.error(getClassName(), ex, "Error merging PDF files.");
        }
        return null;
    }

    private String generateFilename(String recordId, AppDefinition appDef,
            AppService appService, String formDefId) {
        String renameFile = getPropertyString("renameFile");
        String fileName;

        if (renameFile != null && !renameFile.trim().isEmpty()) {
            if (renameFile.contains("{")) {
                fileName = resolveDynamicFilename(renameFile, recordId, appDef, appService, formDefId);
            } else {
                fileName = renameFile.trim();
            }
        } else {
            fileName = "merged_" + System.currentTimeMillis();
        }
        if (!fileName.toLowerCase().endsWith(".pdf")) {
            fileName += ".pdf";
        }

        return fileName;
    }

    private String resolveDynamicFilename(String pattern, String recordId, AppDefinition appDef,
            AppService appService, String formDefId) {
        String resolvedName = pattern;

        try {
            FormRowSet rowSet = appService.loadFormData(
                    appDef.getAppId(),
                    String.valueOf(appDef.getVersion()),
                    formDefId,
                    recordId
            );

            if (rowSet != null && !rowSet.isEmpty()) {
                FormRow row = rowSet.get(0);

                java.util.regex.Pattern placeholderPattern = java.util.regex.Pattern.compile("\\{([^}]+)\\}");
                java.util.regex.Matcher matcher = placeholderPattern.matcher(pattern);

                while (matcher.find()) {
                    String fieldName = matcher.group(1); 
                    String fieldValue = getFieldValue(row, fieldName, recordId);

                    if (fieldValue != null && !fieldValue.isEmpty()) {
                        resolvedName = resolvedName.replace("{" + fieldName + "}", sanitizeFilename(fieldValue));
                    } else {
                        resolvedName = resolvedName.replace("{" + fieldName + "}", recordId);
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.error(getClassName(), e, "Error resolving dynamic filename, using pattern as-is");
            return pattern;
        }

        return resolvedName;
    }

    private String getFieldValue(FormRow row, String fieldName, String recordId) {
        String fieldValue = row.getProperty(fieldName);

        if (fieldValue != null && !fieldValue.isEmpty()) {
            if (fieldValue.contains("/") || fieldValue.contains("\\") || fieldValue.endsWith(".pdf")) {
                return extractFilenameFromUpload(fieldValue);
            } else {
                return fieldValue;
            }
        }

        return null;
    }

    private String extractFilenameFromUpload(String uploadValue) {
        if (uploadValue == null || uploadValue.isEmpty()) {
            return null;
        }

        String[] files = uploadValue.split(";");
        if (files.length > 0) {
            String firstFile = files[0].trim();

            String filename = firstFile;
            if (firstFile.contains("/")) {
                filename = firstFile.substring(firstFile.lastIndexOf("/") + 1);
            }
            if (filename.contains("\\")) {
                filename = filename.substring(filename.lastIndexOf("\\") + 1);
            }

            if (filename.toLowerCase().endsWith(".pdf")) {
                filename = filename.substring(0, filename.length() - 4);
            }

            return filename;
        }

        return null;
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) {
            return "";
        }
        if (filename.toLowerCase().endsWith(".pdf")) {
            filename = filename.substring(0, filename.length() - 4);
        }

        String sanitized = filename.replaceAll("[<>:\"/\\|?*]", "_");
        if (sanitized.length() > 100) {
            sanitized = sanitized.substring(0, 100);
        }

        return sanitized;
    }

    private void saveMergedPdf(byte[] mergedPdfBytes, String formDefIdOutputFile,
            String outputFileFieldId, String recordId,
            AppDefinition appDef, AppService appService) {
        if (mergedPdfBytes == null || mergedPdfBytes.length == 0) {
            LogUtil.warn(getClassName(), "Merged PDF is null or empty; nothing to store.");
            return;
        }
        try {

            String tableName = appService.getFormTableName(appDef, formDefIdOutputFile);
            String uploadPath = FileUtil.getUploadPath(tableName, recordId);

            // Generate filename based on renameFile field
            String fileName = generateFilename(recordId, appDef, appService, formDefIdOutputFile);

            File outputFile = new File(uploadPath, fileName);
            outputFile.getParentFile().mkdirs();
            FileUtils.writeByteArrayToFile(outputFile, mergedPdfBytes);

            FormRow row = new FormRow();
            row.setId(recordId);
            row.put(outputFileFieldId, fileName);
            FormRowSet frs = new FormRowSet();
            frs.add(row);
            appService.storeFormData(appDef.getAppId(), String.valueOf(appDef.getVersion()),
                    formDefIdOutputFile, frs, recordId);

        } catch (IOException ex) {
            LogUtil.error(getClassName(), ex, "Error saving merged PDF file to disk.");
        }
    }

    @Override
    public String getName() {
        return "Merge PDF Tool";
    }

    @Override
    public String getDescription() {
        return "Merges PDF files from multiple fields into a single PDF and saves it into a specified output field.";
    }

    @Override
    public String getVersion() {
        return Activator.VERSION;
    }

    @Override
    public String getLabel() {
        return getPropertyString("label");
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClassName(), "/properties/mergePdfTool.json", null, true, MESSAGE_PATH);
    }
}
