package org.goobi.api.mq.ticket;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.SystemUtils;
import org.goobi.api.mq.TaskTicket;
import org.goobi.api.mq.TicketHandler;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginReturnValue;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Reader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.oned.EAN13Reader;
import com.google.zxing.oned.UPCAReader;
import com.google.zxing.qrcode.QRCodeReader;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.CloseStepHelper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.StepManager;
import lombok.extern.log4j.Log4j;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.dl.Reference;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

/**
 * This Plugin iterates over all images in the image directory of the associated process scanning them for barcodes. If one is found it checks its
 * configuration file to see if the found barcode is associated with a docStruct. If so the image is associated with that docstruct. There is a second
 * category of Docstructs which can be configured, which then continue adding the following Pages to the same struct until a new one starts or a
 * configured terminator is found in a barcode.
 *
 * Possible Extensions:
 *
 * - Add functionality to add Metadata to the page/docstruct through barcodes
 *
 * - Rewrite so the existence of a Barcode type on a Page and not its content triggers the creation of the docStruct
 */
@Log4j
public class BarcodeTicket implements TicketHandler<PluginReturnValue> {
    private static final String LOGICAL_PHYSICAL = "logical_physical";
    private String title = "intranda_step_barcode_scanner";

    private boolean skipWhenDataExists;

    private boolean docByType;

    private Map<String, String> docstructMapString;
    private Map<String, String> docstructMapType;
    private String uuidMetadata;

    /**
     * The reader to be used to find barcodes
     */
    private Reader reader;

    /**
     * Contains pairs of barcodes and associated Docstructnames for Structures spanning multiple pages
     */
    private boolean hasMultipleBarcodes = false;
    /**
     * This Map contains barcodes and the names of DocStruct elements which will create structure elements spanning multiple pages
     */
    private Map<String, String> multiPageDocstructMapString;
    private Map<String, String> multiPageDocstructMapType;

    @Override
    public PluginReturnValue call(TaskTicket ticket) {
        log.info("Barcode ticket for " + ticket.getProcessName());

        Process process = ProcessManager.getProcessById(ticket.getProcessId());
        Prefs prefs = process.getRegelsatz().getPreferences();

        setGlobalFields(process);

        DocStruct physical = null;
        DocStruct logical = null;
        List<String> orderedImageNameList = null;
        Fileformat ff = null;
        DigitalDocument digDoc = null;
        String foldername = null;
        // read image names
        try {
            foldername = process.getImagesOrigDirectory(false);
            orderedImageNameList = StorageProvider.getInstance().list(foldername);
            if (orderedImageNameList.isEmpty()) {
                // abort
                log.info(process.getTitel() + ": no images found");
                return PluginReturnValue.ERROR;
            }
        } catch (IOException | SwapException | DAOException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }

        try {
            // read mets file
            ff = process.readMetadataFile();
            digDoc = ff.getDigitalDocument();
            logical = digDoc.getLogicalDocStruct();
            if (logical.getType().isAnchor()) {
                logical = logical.getAllChildren().get(0);
            }
            physical = digDoc.getPhysicalDocStruct();
            // check if pagination was already written
            List<DocStruct> pages = physical.getAllChildren();
            if (pages != null && !pages.isEmpty()) {
                if (skipWhenDataExists) {
                    return PluginReturnValue.FINISH;
                }
                removeExistingData(physical, logical, ff, pages);
            }

        } catch (ReadException | PreferencesException | IOException | SwapException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }

        DocStructType pageType = prefs.getDocStrctTypeByName("page");
        MetadataType physType = prefs.getMetadataTypeByName("physPageNumber");
        MetadataType logType = prefs.getMetadataTypeByName("logicalPageNumber");
        DocStruct currentMultiPageDS = null;

        for (int index = 0; index < orderedImageNameList.size(); index++) {
            String imageName = orderedImageNameList.get(index);
            try {
                DocStruct dsPage = digDoc.createDocStruct(pageType);

                ContentFile cf = new ContentFile();
                if (SystemUtils.IS_OS_WINDOWS) {
                    cf.setLocation("file:/" + foldername + imageName);
                } else {
                    cf.setLocation("file://" + foldername + imageName);
                }
                dsPage.addContentFile(cf);

                physical.addChild(dsPage);
                Metadata mdPhysPageNo = new Metadata(physType);
                mdPhysPageNo.setValue(String.valueOf(index + 1));
                dsPage.addMetadata(mdPhysPageNo);

                Metadata mdLogicalPageNo = new Metadata(logType);
                dsPage.addMetadata(mdLogicalPageNo);
                logical.addReferenceTo(dsPage, LOGICAL_PHYSICAL);

                // try to detect barcodes on this image
                List<Result> detectedBarcode = readBarcodes(process, imageName);
                if (docByType) {
                    currentMultiPageDS = generateDocStructsFromType(prefs, logical, digDoc, currentMultiPageDS, imageName, dsPage, detectedBarcode);
                } else {
                    currentMultiPageDS = generateDocStructsFromString(prefs, logical, digDoc, currentMultiPageDS, imageName, dsPage, detectedBarcode);
                }
            } catch (TypeNotAllowedForParentException | TypeNotAllowedAsChildException | MetadataTypeNotAllowedException
                    | DocStructHasNoTypeException e) {
                log.error(e);
                return PluginReturnValue.ERROR;
            } catch (IOException | DAOException | InterruptedException | SwapException e) {
                log.error("Unable to read file " + imageName, e);
                return PluginReturnValue.ERROR;
            }
        }
        try {
            process.writeMetadataFile(ff);
        } catch (WriteException | PreferencesException | IOException | SwapException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }
        //close Step
        Step step = StepManager.getStepById(ticket.getStepId());
        CloseStepHelper.closeStep(step, null);
        return PluginReturnValue.FINISH;
    }

    /**
     * Reads Configfile and sets global fields accordingly
     */
    private void setGlobalFields(Process process) {
        String parentStruct = "";
        try {
            parentStruct = process.readMetadataFile().getDigitalDocument().getLogicalDocStruct().getType().getName();
        } catch (PreferencesException | ReadException | IOException | SwapException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        // read config and set object variables accordingly
        XMLConfiguration config = ConfigPlugins.getPluginConfig(title);
        config.setExpressionEngine(new XPathExpressionEngine());
        skipWhenDataExists = config.getBoolean("/skipWhenDataExists", false);
        docByType = config.getBoolean("/docStuctByType", false);
        docstructMapString = new HashMap<>();
        docstructMapType = new HashMap<>();
        uuidMetadata = config.getString("/uuidMetadatum");
        @SuppressWarnings("unchecked")
        List<HierarchicalConfiguration> itemList = config.configurationsAt("/singlePageStructures/item");
        for (HierarchicalConfiguration item : itemList) {
            if (item.getString("@parentStruct").equals(parentStruct)) {
                docstructMapString.put(item.getString("@barcode"), item.getString("@docstruct"));
                docstructMapType.put(item.getString("@type"), item.getString("@docstruct"));
            }
        }
        multiPageDocstructMapString = new HashMap<>();
        multiPageDocstructMapType = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<HierarchicalConfiguration> multiPageItemList = config.configurationsAt("/multipageStructure/item");
        for (HierarchicalConfiguration item : multiPageItemList) {
            if (item.getString("@parentStruct").equals(parentStruct)) {
                multiPageDocstructMapString.put(item.getString("@barcode"), item.getString("@docstruct"));
                multiPageDocstructMapType.put(item.getString("@type"), item.getString("@docstruct"));
            }
        }
        // if this is set true the plugin will look for more than one barcode per image
        hasMultipleBarcodes = config.getBoolean("/multipleBarcodes");
        //this is the reader used to decode the barcodes on images
        switch (config.getString("/reader")) {
            case ("ean13"):
                reader = new EAN13Reader();
            break;
            case ("UPCA"):
                reader = new UPCAReader();
            break;
            case ("qr"):
                reader = new QRCodeReader();
            break;
            case ("multi"):
            default:
                // contains all other readers, slower and more prone to find non existent codes but more versatile
                reader = new MultiFormatReader();
        }
    }

    /**
     * Checks if one or more barcodes are to be read and calls the appropriate method, compiling the return
     *
     * @param process
     * @param imageName
     * @return
     * @throws IOException
     * @throws InterruptedException
     * @throws SwapException
     * @throws DAOException
     */
    private List<Result> readBarcodes(Process process, String imageName) throws IOException, InterruptedException, SwapException, DAOException {
        List<Result> detectedBarcode = new ArrayList<>();
        // needs a wrapper for the reader if there are possibly more than 1 code on the image
        if (hasMultipleBarcodes) {
            GenericMultipleBarcodeReader gmbr = new GenericMultipleBarcodeReader(reader);
            detectedBarcode = decodeMultipleBarcodes(imageName, gmbr, process);
        } else {
            Result tmpBarcode = decodeBarcode(imageName, reader, process);

            if (tmpBarcode != null) {
                detectedBarcode.add(tmpBarcode);
            }
        }
        return detectedBarcode;
    }

    /**
     * Generates the doc struct elements in logical according to the detectedBarcode(s)
     *
     * @param prefs
     * @param logical
     * @param digDoc
     * @param currentMultiPageDS
     * @param imageName
     * @param dsPage
     * @param detectedBarcode
     * @return
     * @throws TypeNotAllowedForParentException
     * @throws TypeNotAllowedAsChildException
     * @throws MetadataTypeNotAllowedException
     */
    private DocStruct generateDocStructsFromString(Prefs prefs, DocStruct logical, DigitalDocument digDoc, DocStruct currentMultiPageDS,
            String imageName, DocStruct dsPage, List<Result> detectedBarcode)
                    throws TypeNotAllowedForParentException, TypeNotAllowedAsChildException, MetadataTypeNotAllowedException {
        for (Result barcode : detectedBarcode) {
            String barcodeString = String.valueOf(barcode);
            log.debug("Barcode found in image " + imageName + " " + barcodeString);
            // check if the barcode matches a single page doc struct, if so add it
            if (docstructMapString.containsKey(barcodeString)) {
                String docstructName = docstructMapString.get(barcodeString);
                log.debug("Barcode " + barcodeString + " is associated with doc struct " + docstructName);
                DocStructType docStructType = prefs.getDocStrctTypeByName(docstructName);
                if (docStructType == null) {
                    log.debug("DocStructType " + docstructName + "not found in ruleset");
                } else {
                    DocStruct ds = digDoc.createDocStruct(docStructType);
                    addBarcodeMetadatum(prefs, barcode, ds);
                    logical.addChild(ds);
                    ds.addReferenceTo(dsPage, LOGICAL_PHYSICAL);
                }
            }
            // check if the barcode matches a multipage structure, if so generate and add it to the logical structure, pages are added later
            if (multiPageDocstructMapString.containsKey(barcodeString)) {
                String docstructName = multiPageDocstructMapString.get(barcodeString);
                if ("DocStructEnd".equals(docstructName)) {
                    currentMultiPageDS = null;
                    continue;
                }
                log.debug("Barcode " + barcodeString + " is associated with doc struct " + docstructName);
                DocStructType docStructType = prefs.getDocStrctTypeByName(docstructName);
                if (docStructType == null) {
                    log.debug("DocStructType " + docstructName + "not found in ruleset");
                } else {
                    currentMultiPageDS = digDoc.createDocStruct(docStructType);
                    logical.addChild(currentMultiPageDS);
                    addBarcodeMetadatum(prefs, barcode, currentMultiPageDS);
                }
            }
        }
        // if currentMultiPageDS is set, all current pages are meant to belong to that multi page structure, so add this one
        if (currentMultiPageDS != null) {
            currentMultiPageDS.addReferenceTo(dsPage, LOGICAL_PHYSICAL);
        }
        return currentMultiPageDS;
    }

    /**
     * Generates the doc struct elements in logical according to the detectedBarcode(s)
     *
     * @param prefs
     * @param logical
     * @param digDoc
     * @param currentMultiPageDS
     * @param imageName
     * @param dsPage
     * @param detectedBarcode
     * @return
     * @throws TypeNotAllowedForParentException
     * @throws TypeNotAllowedAsChildException
     * @throws MetadataTypeNotAllowedException
     */
    private DocStruct generateDocStructsFromType(Prefs prefs, DocStruct logical, DigitalDocument digDoc, DocStruct currentMultiPageDS,
            String imageName, DocStruct dsPage, List<Result> detectedBarcode)
                    throws TypeNotAllowedForParentException, TypeNotAllowedAsChildException, MetadataTypeNotAllowedException {
        for (Result barcode : detectedBarcode) {
            String barcodeType = barcode.getBarcodeFormat().toString();
            log.debug("Barcode found in image " + imageName + " " + barcodeType);
            // check if the barcode matches a single page doc struct, if so add it
            if (docstructMapType.containsKey(barcodeType)) {
                String docstructName = docstructMapType.get(barcodeType);
                log.debug("Barcode " + barcodeType + " is associated with doc struct " + docstructName);
                DocStructType docStructType = prefs.getDocStrctTypeByName(docstructName);
                if (docStructType == null) {
                    log.debug("DocStructType " + docstructName + "not found in ruleset");
                } else {
                    DocStruct ds = digDoc.createDocStruct(docStructType);
                    addBarcodeMetadatum(prefs, barcode, ds);
                    logical.addChild(ds);
                    ds.addReferenceTo(dsPage, LOGICAL_PHYSICAL);
                }
            }
            // check if the barcode matches a multipage structure, if so generate and add it to the logical structure, pages are added later
            if (multiPageDocstructMapType.containsKey(barcodeType)) {
                String docstructName = multiPageDocstructMapType.get(barcodeType);
                if ("DocStructEnd".equals(docstructName)) {
                    currentMultiPageDS = null;
                    continue;
                }
                log.debug("Barcode " + barcodeType + " is associated with doc struct " + docstructName);
                DocStructType docStructType = prefs.getDocStrctTypeByName(docstructName);
                if (docStructType == null) {
                    log.debug("DocStructType " + docstructName + "not found in ruleset");
                } else {
                    currentMultiPageDS = digDoc.createDocStruct(docStructType);
                    logical.addChild(currentMultiPageDS);
                    addBarcodeMetadatum(prefs, barcode, currentMultiPageDS);
                }
            }
        }
        // if currentMultiPageDS is set, all current pages are meant to belong to that multi page structure, so add this one
        if (currentMultiPageDS != null) {
            currentMultiPageDS.addReferenceTo(dsPage, LOGICAL_PHYSICAL);
        }
        return currentMultiPageDS;
    }

    private void addBarcodeMetadatum(Prefs prefs, Result barcode, DocStruct ds) throws MetadataTypeNotAllowedException {
        if (uuidMetadata != null && !uuidMetadata.isEmpty()) {
            Metadata md = new Metadata(prefs.getMetadataTypeByName(uuidMetadata));
            md.setValue(barcode.getText());
            ds.addMetadata(md);
        }
    }

    private void removeExistingData(DocStruct physical, DocStruct logical, Fileformat ff, List<DocStruct> pages) throws PreferencesException {
        // process contains data, clear it
        for (DocStruct page : pages) {
            ff.getDigitalDocument().getFileSet().removeFile(page.getAllContentFiles().get(0));
            List<Reference> refs = new ArrayList<>(page.getAllFromReferences());
            for (ugh.dl.Reference ref : refs) {
                ref.getSource().removeReferenceTo(page);
            }
        }
        while (physical.getAllChildren() != null && !physical.getAllChildren().isEmpty()) {
            physical.removeChild(physical.getAllChildren().get(0));
        }
        while (logical.getAllChildren() != null && !logical.getAllChildren().isEmpty()) {
            logical.removeChild(logical.getAllChildren().get(0));
        }
    }

    @Override
    public String getTicketHandlerName() {
        return "BarcodeScanner";
    }

    /**
     * Searches for a barcode on image found at fileName in the image folder of process using passed reader
     *
     * @param fileName
     * @param mfr
     * @param process
     * @return
     * @throws IOException
     * @throws DAOException
     * @throws InterruptedException
     * @throws SwapException
     */
    private static Result decodeBarcode(String fileName, Reader mfr, Process process)
            throws IOException, DAOException, InterruptedException, SwapException {
        try (InputStream is = StorageProvider.getInstance().newInputStream(Paths.get(process.getImagesOrigDirectory(false), fileName))) {
            BufferedImage image = ImageIO.read(is);
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bm = new BinaryBitmap(new HybridBinarizer(source));
            Result tmpResult = null;
            Map<DecodeHintType, Object> tmpHintsMap = new EnumMap<>(DecodeHintType.class);
            tmpHintsMap.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            try {
                tmpResult = mfr.decode(bm, tmpHintsMap);
            } catch (NotFoundException e) {
                log.debug("No barcode found on image " + fileName);
                return null;
            } catch (FormatException e) {
                log.debug("Found barcode on image " + fileName + " but it did not conform to configured characteristics");
                return null;
            } catch (ChecksumException e) {
                log.debug("Found barcode on image " + fileName + " but its checksum did not match");
                return null;
            }
            //            String result = String.valueOf(tmpResult);

            return tmpResult;
        }
    }

    /**
     * Detects barcodes in the image with name fileName in the image folder of process using passed Reader, allows for multiple Codes to be detected
     *
     * @param fileName
     * @param mbr
     * @param process
     * @return
     * @throws IOException
     * @throws InterruptedException
     * @throws SwapException
     * @throws DAOException
     */
    private static List<Result> decodeMultipleBarcodes(String fileName, GenericMultipleBarcodeReader mbr, Process process)
            throws IOException, InterruptedException, SwapException, DAOException {
        try (InputStream is = StorageProvider.getInstance().newInputStream(Paths.get(process.getImagesOrigDirectory(false), fileName))) {
            BufferedImage image = ImageIO.read(is);

            //        File file = new File(fileName);
            //        BufferedImage image = ImageIO.read(file);
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bm = new BinaryBitmap(new HybridBinarizer(source));
            Result[] tmpResult = null;
            Map<DecodeHintType, Object> tmpHintsMap = new EnumMap<>(DecodeHintType.class);
            tmpHintsMap.put(DecodeHintType.TRY_HARDER, Boolean.FALSE);
            try {
                tmpResult = mbr.decodeMultiple(bm, tmpHintsMap);
            } catch (NotFoundException e) {
                log.debug("No barcode found on image " + fileName);
                return new ArrayList<>();
            }
            List<Result> result = new ArrayList<>();
            for (Result resultObject : tmpResult) {
                result.add(resultObject);
            }

            return result;
        }
    }
}
