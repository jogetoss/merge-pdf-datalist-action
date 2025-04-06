package org.joget.marketplace;

import java.util.ArrayList;
import java.util.Collection;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

public class Activator implements BundleActivator {

    public final static String VERSION = "8.0.2";
    protected Collection<ServiceRegistration> registrationList;

    public void start(BundleContext context) {
        registrationList = new ArrayList<ServiceRegistration>();

        //Register plugin here
        registrationList.add(context.registerService(MergePdfDatalistAction.class.getName(), new MergePdfDatalistAction(), null));
        registrationList.add(context.registerService(MergePDFTool.class.getName(), new MergePDFTool(), null));

    }

    public void stop(BundleContext context) {
        for (ServiceRegistration registration : registrationList) {
            registration.unregister();
        }
    }
}
