package de.intranda.goobi.plugins;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.SystemUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

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
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Data;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
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

@Data
@Log4j
@PluginImplementation
public class BarcodeScanner implements IStepPluginVersion2 {

    private PluginGuiType pluginGuiType = PluginGuiType.NONE;

    private String title = "intranda_step_barcode_scanner";

    private PluginType type = PluginType.Step;

    private Step step;

    private Pattern imagePattern;

    private boolean skipWhenDataExists;

    private Map<String, String> docstructMap;

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
    private Map<String, String> multiPageDocstructMap;
    private static final String LOGICAL_PHYSICAL = "logical_physical";

    public BarcodeScanner() {

        XMLConfiguration config = ConfigPlugins.getPluginConfig(title);
        config.setExpressionEngine(new XPathExpressionEngine());
        skipWhenDataExists = config.getBoolean("/skipWhenDataExists", false);
        String regexp = config.getString("/paginationRegex");
        imagePattern = Pattern.compile(regexp);

        docstructMap = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<HierarchicalConfiguration> itemList = config.configurationsAt("/singlePageStructures/item");
        for (HierarchicalConfiguration item : itemList) {
            docstructMap.put(item.getString("@barcode"), item.getString("@docstruct"));
        }
        multiPageDocstructMap = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<HierarchicalConfiguration> multiPageItemList = config.configurationsAt("/multipageStructures/item");
        for (HierarchicalConfiguration item : multiPageItemList) {
            multiPageDocstructMap.put(item.getString("@barcode"), item.getString("@docstruct"));
        }
        // if this is set true the plugin will look for more than one barcode per image
        hasMultipleBarcodes = config.getBoolean("/multipleBarcodes");
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

    @Override
    public PluginReturnValue run() {

        Process process = step.getProzess();
        Prefs prefs = process.getRegelsatz().getPreferences();
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
        } catch (IOException | InterruptedException | SwapException | DAOException e) {
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

        } catch (ReadException | PreferencesException | WriteException | IOException | InterruptedException | SwapException | DAOException e) {
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
                List<String> detectedBarcode = readBarcodes(process, imageName);

                currentMultiPageDS = generateDocStructs(prefs, logical, digDoc, currentMultiPageDS, imageName, dsPage, detectedBarcode);

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
        } catch (WriteException | PreferencesException | IOException | InterruptedException | SwapException | DAOException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }

        return PluginReturnValue.FINISH;
    }

    private List<String> readBarcodes(Process process, String imageName) throws IOException, InterruptedException, SwapException, DAOException {
        List<String> detectedBarcode = new ArrayList<>();
        // needs a wrapper for the reader if there are possibly more than 1 code on the image
        if (hasMultipleBarcodes) {
            GenericMultipleBarcodeReader gmbr = new GenericMultipleBarcodeReader(reader);
            detectedBarcode = decodeMultipleBarcodes(imageName, gmbr, process);
        } else {
            String tmpBarcode = decodeBarcode(imageName, reader, process);

            if (tmpBarcode != null) {
                detectedBarcode.add(tmpBarcode);
            }
        }
        return detectedBarcode;
    }

    private DocStruct generateDocStructs(Prefs prefs, DocStruct logical, DigitalDocument digDoc, DocStruct currentMultiPageDS, String imageName,
            DocStruct dsPage, List<String> detectedBarcode) throws TypeNotAllowedForParentException, TypeNotAllowedAsChildException {
        for (String barcode : detectedBarcode) {
            log.debug("Barcode found in image " + imageName + " " + barcode);
            // check if the barcode matches a single page doc struct, if so add it
            if (docstructMap.containsKey(barcode)) {
                String docstructName = docstructMap.get(barcode);
                log.debug("Barcode " + barcode + " is associated with doc struct " + docstructName);
                DocStructType docStructType = prefs.getDocStrctTypeByName(docstructName);
                if (docStructType == null) {
                    log.debug("DocStructType " + docstructName + "not found in ruleset");
                } else {
                    DocStruct ds = digDoc.createDocStruct(docStructType);
                    logical.addChild(ds);
                    ds.addReferenceTo(dsPage, LOGICAL_PHYSICAL);
                }
            }
            // check if the barcode matches a multipage structure, if so generate and add it to the logical structure, pages are added later
            if (multiPageDocstructMap.containsKey(barcode)) {
                String docstructName = multiPageDocstructMap.get(barcode);
                if ("DocStructEnd".equals(docstructName)) {
                    currentMultiPageDS = null;
                    continue;
                }
                log.debug("Barcode " + barcode + " is associated with doc struct " + docstructName);
                DocStructType docStructType = prefs.getDocStrctTypeByName(docstructName);
                if (docStructType == null) {
                    log.debug("DocStructType " + docstructName + "not found in ruleset");
                } else {
                    currentMultiPageDS = digDoc.createDocStruct(docStructType);
                    logical.addChild(currentMultiPageDS);
                }
            }
        }
        // if currentMultiPageDS is set, all current pages are meant to belong to that multi page structure, so add this one
        if (currentMultiPageDS != null) {
            currentMultiPageDS.addReferenceTo(dsPage, LOGICAL_PHYSICAL);
        }
        return currentMultiPageDS;
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
    public String cancel() {
        return null;
    }

    @Override
    public boolean execute() {
        PluginReturnValue val = run();

        return val == PluginReturnValue.FINISH;
    }

    @Override
    public String finish() {
        return null;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    private static String decodeBarcode(String fileName, Reader mfr, Process process)
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
            String result = String.valueOf(tmpResult);

            return result;
        }
    }

    private static List<String> decodeMultipleBarcodes(String fileName, GenericMultipleBarcodeReader mbr, Process process)
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
            List<String> result = new ArrayList<>();
            for (Result resultObject : tmpResult) {
                result.add(String.valueOf(resultObject));
            }

            return result;
        }
    }
}
