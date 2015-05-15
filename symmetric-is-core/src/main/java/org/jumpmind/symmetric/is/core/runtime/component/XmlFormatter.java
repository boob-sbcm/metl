package org.jumpmind.symmetric.is.core.runtime.component;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.jdom2.Attribute;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.filter.ElementFilter;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.input.sax.XMLReaders;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;
import org.jumpmind.properties.TypedProperties;
import org.jumpmind.symmetric.is.core.model.ComponentAttributeSetting;
import org.jumpmind.symmetric.is.core.model.ComponentEntitySetting;
import org.jumpmind.symmetric.is.core.model.Model;
import org.jumpmind.symmetric.is.core.model.ModelAttribute;
import org.jumpmind.symmetric.is.core.model.Setting;
import org.jumpmind.symmetric.is.core.model.SettingDefinition;
import org.jumpmind.symmetric.is.core.model.SettingDefinition.Type;
import org.jumpmind.symmetric.is.core.runtime.EntityData;
import org.jumpmind.symmetric.is.core.runtime.LogLevel;
import org.jumpmind.symmetric.is.core.runtime.Message;
import org.jumpmind.symmetric.is.core.runtime.flow.IMessageTarget;

@ComponentDefinition(
        typeName = XmlFormatter.TYPE,
        category = ComponentCategory.PROCESSOR,
        iconImage = "xmlformatter.png",
        inputMessage = MessageType.ENTITY,
        outgoingMessage = MessageType.TEXT)
public class XmlFormatter extends AbstractComponentRuntime {

    @SettingDefinition(
            order = 10,
            required = false,
            type = Type.BOOLEAN,
            label = "Ignore namespaces for XPath matching",
            defaultValue = "true")
    public final static String IGNORE_NAMESPACE = "xml.formatter.ignore.namespace";

    public static final String TYPE = "Format XML";

    public final static String XML_FORMATTER_XPATH = "xml.formatter.xpath";

    public final static String XML_FORMATTER_TEMPLATE = "xml.formatter.template";

    Document templateDocument;
    
    List<XmlFormatterAttributeSetting> attributeSettings;
       
    Map<String, XmlFormatterEntitySetting> entitySettings;
    
    boolean ignoreNamespace = true;

    @Override
    protected void start() {
        TypedProperties properties = getComponent().toTypedProperties(getSettingDefinitions(false));
        ignoreNamespace = properties.is(IGNORE_NAMESPACE);
        Setting templateSetting = getComponent().findSetting(XML_FORMATTER_TEMPLATE);

        if (templateSetting != null && StringUtils.isNotBlank(templateSetting.getValue())) {
            SAXBuilder builder = new SAXBuilder();
            builder.setXMLReaderFactory(XMLReaders.NONVALIDATING);
            builder.setFeature("http://xml.org/sax/features/validation", false);
            try {
                templateDocument = builder.build(new StringReader(templateSetting.getValue()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        Model model = getComponent().getInputModel();
        entitySettings = new HashMap<String, XmlFormatterEntitySetting>();
        attributeSettings = new ArrayList<XmlFormatterAttributeSetting>();

        if (model != null) {
            Map<Element, Namespace> namespaces = removeNamespaces(templateDocument);
            for (ComponentEntitySetting compEntitySetting : getComponent().getEntitySettings()) {
                if (compEntitySetting.getName().equals(XML_FORMATTER_XPATH)) {
                    XPathExpression<Element> expression = XPathFactory.instance().compile(compEntitySetting.getValue(), Filters.element());
                    List<Element> matches = expression.evaluate(templateDocument.getRootElement());
                    if (matches.size() == 0) {
                        log(LogLevel.WARN, "XPath expression " + compEntitySetting.getValue() + " did not find any matches");
                    } else {
                        Element templateElement = matches.get(0);
                        entitySettings.put(compEntitySetting.getEntityId(), new XmlFormatterEntitySetting(compEntitySetting, expression, templateElement));
                    }
                }
            }
            restoreNamespaces(templateDocument, namespaces);
            
            for (ComponentAttributeSetting compAttrSetting : getComponent().getAttributeSettings()) {
                if (compAttrSetting.getName().equals(XML_FORMATTER_XPATH)) {
                    ModelAttribute attr = model.getAttributeById(compAttrSetting.getAttributeId());
                    if (attr != null) {
                        XPathExpression<Object> expression = XPathFactory.instance().compile(compAttrSetting.getValue());
                        XmlFormatterEntitySetting entitySetting = entitySettings.get(attr.getEntityId());
                        XmlFormatterAttributeSetting attrSetting = new XmlFormatterAttributeSetting(compAttrSetting, expression);
                        if (entitySetting != null) {
                            entitySetting.getAttributeSettings().add(attrSetting);
                        } else {
                            attributeSettings.add(attrSetting);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void handle(Message inputMessage, IMessageTarget messageTarget) {
        getComponentStatistics().incrementInboundMessages();
        ArrayList<EntityData> inputRows = inputMessage.getPayload();

        Message outputMessage = new Message(getFlowStepId());
        ArrayList<String> outputPayload = new ArrayList<String>();

        Document document = templateDocument.clone();
        Namespace rootNamespace = document.getRootElement().getNamespace();
        Map<Element, Namespace> namespaces = removeNamespaces(document);

        for (XmlFormatterEntitySetting entitySetting : entitySettings.values()) {
            List<Element> matches = entitySetting.getExpression().evaluate(document.getRootElement());
            for (Element element : matches) {
                entitySetting.setParentElement(element.getParentElement());
            }            
        }

        for (EntityData inputRow : inputRows) {
            processInputRow(document, inputRow);
        }

        restoreNamespaces(document, namespaces);
        document.getRootElement().setNamespace(rootNamespace);

        XMLOutputter xmlOutputter = new XMLOutputter();
        xmlOutputter.setFormat(Format.getPrettyFormat());
        outputPayload.add(xmlOutputter.outputString(document));
        outputMessage.setPayload(outputPayload);
        log(LogLevel.INFO, outputPayload.toString());
        getComponentStatistics().incrementOutboundMessages();
        outputMessage.getHeader().setSequenceNumber(getComponentStatistics().getNumberOutboundMessages());
        outputMessage.getHeader().setLastMessage(inputMessage.getHeader().isLastMessage());
        messageTarget.put(outputMessage);
    }

    private void processInputRow(Document document, EntityData inputRow) {
        Set<XmlFormatterEntitySetting> inputEntitySettings = getEntitySettings(inputRow);

        // apply attributes whose entities do not need to repeat
        applyAttributeXpath(document, inputRow, attributeSettings);
        
        // apply attributes whose entities are supposed to repeat
        for (XmlFormatterEntitySetting entitySetting : inputEntitySettings) {
            if (entitySetting.isFirstTimeApply()) {
                applyAttributeXpath(document, inputRow, entitySetting.getAttributeSettings());
                entitySetting.setFirstTimeApply(false);
            } else {
                Map<Element, Namespace> namespaces = removeNamespaces(templateDocument);
                applyAttributeXpath(templateDocument, inputRow, entitySetting.getAttributeSettings());
                restoreNamespaces(templateDocument, namespaces);
                Element clonedElement = entitySetting.getTemplateElement().clone();
                entitySetting.getParentElement().addContent(clonedElement);
            }
        }
    }

    private Set<XmlFormatterEntitySetting> getEntitySettings(EntityData inputRow) {
        Set<XmlFormatterEntitySetting> entitySettingSet = new HashSet<XmlFormatterEntitySetting>();
        Model model = getComponent().getInputModel();
        if (model != null && inputRow.size() > 0) {
            for (String attributeId : inputRow.keySet()) {
                ModelAttribute attribute = model.getAttributeById(attributeId);
                if (attribute != null) {
                    XmlFormatterEntitySetting entitySetting = entitySettings.get(attribute.getEntityId());
                    if (entitySetting != null) {
                        entitySettingSet.add(entitySetting);
                    }
                }
            }
        }
        return entitySettingSet;
    }

    private void applyAttributeXpath(Document document, EntityData inputRow, List<XmlFormatterAttributeSetting> settings) {
        for (XmlFormatterAttributeSetting setting : settings) {
            String attributeId = setting.getSetting().getAttributeId();
            if (inputRow.containsKey(attributeId)) {
                Object inputValue = inputRow.get(setting.getSetting().getAttributeId());
                String value = (inputValue == null) ? null : inputValue.toString();
                List<Object> matches = setting.getExpression().evaluate(document.getRootElement());
                if (matches.size() == 0) {
                    log(LogLevel.WARN, "XPath expression " + setting.getExpression().getExpression() + " did not find any matches");
                }
                for (Object object : matches) {
                    if (object instanceof Element) {
                        ((Element) object).setText(value);
                    } else if (object instanceof Attribute) {
                        ((Attribute) object).setValue(value);
                    }
                }
            }
        }        
    }

    private Map<Element, Namespace> removeNamespaces(Document document) {
        Map<Element, Namespace> namespaces = new HashMap<Element, Namespace>();
        if (ignoreNamespace) {
            document.getRootElement().setNamespace(null);
            for (Element el : document.getRootElement().getDescendants(new ElementFilter())) {
                Namespace nsp = el.getNamespace();
                if (nsp != null) {
                    el.setNamespace(null);
                    namespaces.put(el, nsp);
                }
            }
        }
        return namespaces;
    }

    private void restoreNamespaces(Document document, Map<Element, Namespace> namespaces) {
        if (ignoreNamespace) {
            Set<Element> elements = namespaces.keySet();
            for (Element element : elements) {
                element.setNamespace(namespaces.get(element));
            }
        }
    }

    class XmlFormatterAttributeSetting {

        ComponentAttributeSetting setting;

        XPathExpression<Object> expression;
        
        XmlFormatterAttributeSetting(ComponentAttributeSetting setting, XPathExpression<Object> expression) {
            this.setting = setting;
            this.expression = expression;
        }

        public ComponentAttributeSetting getSetting() {
            return setting;
        }

        public void setSetting(ComponentAttributeSetting setting) {
            this.setting = setting;
        }

        public XPathExpression<Object> getExpression() {
            return expression;
        }

        public void setExpression(XPathExpression<Object> expression) {
            this.expression = expression;
        }
    }

    class XmlFormatterEntitySetting {
        
        ComponentEntitySetting setting;

        XPathExpression<Element> expression;
        
        Element templateElement;
        
        Element parentElement;
        
        List<XmlFormatterAttributeSetting> attributeSettings;
        
        boolean firstTimeApply;
        
        XmlFormatterEntitySetting(ComponentEntitySetting setting, XPathExpression<Element> expression, Element templateElement) {
            this.setting = setting;
            this.expression = expression;
            this.templateElement = templateElement;
            this.attributeSettings = new ArrayList<XmlFormatterAttributeSetting>();
            this.firstTimeApply = true;
        }

        public ComponentEntitySetting getSetting() {
            return setting;
        }

        public void setSetting(ComponentEntitySetting setting) {
            this.setting = setting;
        }

        public XPathExpression<Element> getExpression() {
            return expression;
        }

        public void setExpression(XPathExpression<Element> expression) {
            this.expression = expression;
        }

        public Element getTemplateElement() {
            return templateElement;
        }

        public void setTemplateElement(Element matchingElement) {
            this.templateElement = matchingElement;
        }

        public List<XmlFormatterAttributeSetting> getAttributeSettings() {
            return attributeSettings;
        }

        public void setAttributeSettings(
                List<XmlFormatterAttributeSetting> attributeSettings) {
            this.attributeSettings = attributeSettings;
        }

        public Element getParentElement() {
            return parentElement;
        }

        public void setParentElement(Element parentElement) {
            this.parentElement = parentElement;
        }

        public boolean isFirstTimeApply() {
            return firstTimeApply;
        }

        public void setFirstTimeApply(boolean firstTimeApply) {
            this.firstTimeApply = firstTimeApply;
        }
    }
}