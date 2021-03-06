
package org.jgroups.conf;

import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.ChannelException;
import org.jgroups.JChannel;
import org.jgroups.util.Util;
import org.w3c.dom.Element;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AccessControlException;
import java.util.List;
import java.util.Map;

/**
 * The ConfigurationFactory is a factory that returns a protocol stack configurator.
 * The protocol stack configurator is an object that read a stack configuration and
 * parses it so that the ProtocolStack can create a stack.
 * <BR>
 * Currently the factory returns one of the following objects:<BR>
 * 1. XmlConfigurator - parses XML files<BR>
 * 2. PlainConfigurator - uses the old style strings UDP:FRAG: etc etc<BR>
 *
 * @author Filip Hanik (<a href="mailto:filip@filip.net">filip@filip.net)
 * @author Bela Ban
 */
public class ConfiguratorFactory {
    public static final String JAXP_MISSING_ERROR_MSG=
            "JAXP Error: the required XML parsing classes are not available; " +
            "make sure that JAXP compatible libraries are in the classpath.";

    static final Log log=LogFactory.getLog(ConfiguratorFactory.class);


    protected ConfiguratorFactory() {
    }

    /**
     * Returns a protocol stack configurator based on the XML configuration provided by the specified File.
     *
     * @param file a File with a JGroups XML configuration.
     *
     * @return a <code>ProtocolStackConfigurator</code> containing the stack configuration.
     *
     * @throws ChannelException if problems occur during the configuration of the protocol stack.
     */
    public static ProtocolStackConfigurator getStackConfigurator(File file) throws ChannelException {
        try {
            checkJAXPAvailability();
            InputStream input=getConfigStream(file);
            return XmlConfigurator.getInstance(input);
        }
        catch(Exception ex) {
            throw createChannelConfigurationException(ex);
        }
    }

    /**
     * Returns a protocol stack configurator based on the XML configuration provided at the specified URL.
     *
     * @param url a URL pointing to a JGroups XML configuration.
     *
     * @return a <code>ProtocolStackConfigurator</code> containing the stack configuration.
     *
     * @throws ChannelException if problems occur during the configuration of the protocol stack.
     */
    public static ProtocolStackConfigurator getStackConfigurator(URL url) throws ChannelException {
        try {
            checkForNullConfiguration(url);
            checkJAXPAvailability();
            return XmlConfigurator.getInstance(url);
        }
        catch (IOException ioe) {
            throw createChannelConfigurationException(ioe);
        }
    }

    /**
     * Returns a protocol stack configurator based on the XML configuration provided by the specified XML element.
     *
     * @param element a XML element containing a JGroups XML configuration.
     *
     * @return a <code>ProtocolStackConfigurator</code> containing the stack configuration.
     *
     * @throws ChannelException if problems occur during the configuration of the protocol stack.
     */
    public static ProtocolStackConfigurator getStackConfigurator(Element element) throws ChannelException {
        try {
            checkForNullConfiguration(element);
            return XmlConfigurator.getInstance(element);
        }
        catch (IOException ioe) {
            throw createChannelConfigurationException(ioe);
        }
    }

    /**
     * Returns a protocol stack configurator based on the provided properties
     * string.
     *
     * @param properties an old style property string, a string representing a system resource containing a JGroups
     *                   XML configuration, a string representing a URL pointing to a JGroups XML configuration,
     *                   or a string representing a file name that contains a JGroups XML configuration.
     */
    public static ProtocolStackConfigurator getStackConfigurator(String properties) throws ChannelException {
        // added by bela: for null String props we use the default properties
        if(properties == null)
            properties=JChannel.DEFAULT_PROTOCOL_STACK;

        // Attempt to treat the properties string as a pointer to an XML configuration.
        XmlConfigurator configurator = null;

        try {
            checkForNullConfiguration(properties);
            configurator=getXmlConfigurator(properties);
        }
        catch (IOException ioe) {
            throw createChannelConfigurationException(ioe);
        }

        // Did the properties string point to a JGroups XML configuration?
        if (configurator != null) {
            return configurator;
        }
        else {
            // Attempt to process the properties string as the old style property string.
            return new PlainConfigurator(properties);
        }
    }



    public static InputStream getConfigStream(File file) throws Exception {
        try {
            checkForNullConfiguration(file);
            return new FileInputStream(file);
        }
        catch(IOException ioe) {
            throw createChannelConfigurationException(ioe);
        }
    }



    public static InputStream getConfigStream(URL url) throws Exception {
        try {
            checkJAXPAvailability();
            return url.openStream();
        }
        catch(Exception ex) {
            throw createChannelConfigurationException(ex);
        }
    }



    /**
     * Returns a JGroups XML configuration InputStream based on the provided properties string.
     *
     * @param properties a string representing a system resource containing a JGroups XML configuration, a string
     *                   representing a URL pointing to a JGroups ML configuration, or a string representing
     *                   a file name that contains a JGroups XML configuration.
     *
     * @throws IOException  if the provided properties string appears to be a valid URL but is unreachable.
     */
    public static InputStream getConfigStream(String properties) throws IOException {
        InputStream configStream = null;

        // Check to see if the properties string is the name of a file.
        try {
            configStream=new FileInputStream(properties);
        }
        catch(FileNotFoundException fnfe) {
            // the properties string is likely not a file
        }
        catch(AccessControlException access_ex) {
            // fixes http://jira.jboss.com/jira/browse/JGRP-94
        }

        // Check to see if the properties string is a URL.
        if(configStream == null) {
            try {
                configStream=new URL(properties).openStream();
            }
            catch (MalformedURLException mre) {
                // the properties string is not a URL
            }
        }
        // Commented so the caller is notified of this condition, but left in
        // the code for documentation purposes.
        //
        // catch (IOException ioe) {
            // the specified URL string was not reachable
        // }

        // Check to see if the properties string is the name of a resource,
        // e.g. udp.xml.
        if(configStream == null && properties.endsWith("xml")) {
            configStream=Util.getResourceAsStream(properties, ConfiguratorFactory.class);
        }
        return configStream;
    }


    public static InputStream getConfigStream(Object properties) throws IOException {
        InputStream input=null;

        // added by bela: for null String props we use the default properties
        if(properties == null)
            properties=JChannel.DEFAULT_PROTOCOL_STACK;

        if(properties instanceof URL) {
            try {
                input=((URL)properties).openStream();
            }
            catch(Throwable t) {
            }
        }

        // if it is a string, then it could be a plain string or a url
        if(input == null && properties instanceof String) {
            input=getConfigStream((String)properties);
        }

        // try a regular file
        if(input == null && properties instanceof File) {
            try {
                input=new FileInputStream((File)properties);
            }
            catch(Throwable t) {
            }
        }

        if(input != null)
            return input;

        if(properties instanceof Element) {
            return getConfigStream(properties);
        }

        return new ByteArrayInputStream(((String)properties).getBytes());
    }




    /**
     * Returns an XmlConfigurator based on the provided properties string (if possible).
     *
     * @param properties a string representing a system resource containing a
     *                   JGroups XML configuration, a string representing a URL
     *                   pointing to a JGroups ML configuration, or a string
     *                   representing a file name that contains a JGroups XML
     *                   configuration.
     *
     * @return an XmlConfigurator instance based on the provided properties
     *         string; <code>null</code> if the provided properties string does
     *         not point to an XML configuration.
     *
     * @throws IOException  if the provided properties string appears to be a
     *                      valid URL but is unreachable, or if the JGroups XML
     *                      configuration pointed to by the URL can not be
     *                      parsed.
     */
    static XmlConfigurator getXmlConfigurator(String properties) throws IOException {
        XmlConfigurator returnValue=null;
        InputStream configStream=getConfigStream(properties);

        if (configStream != null) {
            checkJAXPAvailability();
            returnValue=XmlConfigurator.getInstance(configStream);
        }

        return returnValue;
    }

    /**
     * Creates a <code>ChannelException</code> instance based upon a
     * configuration problem.
     *
     * @param cause the exceptional configuration condition to be used as the
     *              created <code>ChannelException</code>'s cause.
     */
    static ChannelException createChannelConfigurationException(Throwable cause) {
        return new ChannelException("unable to load the protocol stack", cause);
    }

    /**
     * Check to see if the specified configuration properties are
     * <code>null</null> which is not allowed.
     *
     * @param properties the specified protocol stack configuration.
     *
     * @throws NullPointerException if the specified configuration properties
     *                              are <code>null</code>.
     */
    static void checkForNullConfiguration(Object properties) {
        if(properties == null)
            throw new NullPointerException("the specifed protocol stack configuration was null");
    }

    /**
     * Checks the availability of the JAXP classes on the classpath.
     *
     * @throws NoClassDefFoundError if the required JAXP classes are not
     *                              availabile on the classpath.
     */
    static void checkJAXPAvailability() {
        try {
            // TODO: Do some real class checking here instead of forcing the
            //       load of a JGroups class that happens (by default) to do it
            //       for us.
            XmlConfigurator.class.getName();
        }
        catch (NoClassDefFoundError error) {
            Error tmp=new NoClassDefFoundError(JAXP_MISSING_ERROR_MSG);
            tmp.initCause(error);
            throw tmp;
        }
    }

    /**
     * Replace variables of the form ${var:default} with the getProperty(var,
     * default)
     * 
     * @param configurator
     */
    public static void substituteVariables(ProtocolStackConfigurator configurator) {
        List<ProtocolConfiguration> protocols=configurator.getProtocolStack();
        for(ProtocolConfiguration data: protocols) {
            if(data != null) {
                Map<String,String> parms=data.getProperties();
                for(Map.Entry<String,String> entry:parms.entrySet()) {
                    String val=entry.getValue();
                    String replacement=Util.substituteVariable(val);
                    if(!replacement.equals(val)) {
                        entry.setValue(replacement);
                    }
                }
            }
        }
    }          
}









