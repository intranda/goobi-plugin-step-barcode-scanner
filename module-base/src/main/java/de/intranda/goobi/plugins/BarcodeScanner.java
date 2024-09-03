package de.intranda.goobi.plugins;

import java.util.HashMap;
import java.util.regex.Pattern;

import org.goobi.api.mq.QueueType;
import org.goobi.api.mq.TaskTicket;
import org.goobi.api.mq.TicketGenerator;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import jakarta.jms.JMSException;
import lombok.Data;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@Data
@Log4j
@PluginImplementation
public class BarcodeScanner implements IStepPluginVersion2 {

    private PluginGuiType pluginGuiType = PluginGuiType.NONE;

    private String title = "intranda_step_barcode_scanner";

    private PluginType type = PluginType.Step;

    private Step step;

    private Pattern imagePattern;

    /* (non-Javadoc)
     * @see org.goobi.production.plugin.interfaces.IStepPluginVersion2#run()
     * Creates Ticket for Barcode Scanner and adds it to queue
     */
    @Override
    public PluginReturnValue run() {

        TaskTicket exportTicket = TicketGenerator.generateSimpleTicket("BarcodeScanner");
        exportTicket.setProcessId(step.getProzess().getId());
        exportTicket.setProcessName(step.getProzess().getTitel());

        exportTicket.setStepId(step.getId());
        exportTicket.setStepName(step.getTitel());

        try {
            TicketGenerator.submitInternalTicket(exportTicket, QueueType.SLOW_QUEUE, "BarcodeScanner", step.getProcessId());
        } catch (JMSException e) {
            return PluginReturnValue.ERROR;
        }

        return PluginReturnValue.WAIT;

    }

    @Override
    public String cancel() {
        return null;
    }

    @Override
    public boolean execute() {
        PluginReturnValue val = run();
        return false;
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

}
