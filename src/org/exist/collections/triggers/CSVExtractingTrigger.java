package org.exist.collections.triggers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Stack;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import org.exist.collections.Collection;
import org.exist.collections.CollectionConfigurationException;
import org.exist.dom.DocumentImpl;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.xmldb.XmldbURI;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

/**
 * Extracts CSV data from an element into a number of new child elements
 *
 * Mainly designed to be used at STORE event, but should also be usable at UPDATE event
 *
 * Example configuration -
 *
 * <collection xmlns="http://exist-db.org/collection-config/1.0">
 *   <triggers>
 *       <trigger event="store" class="org.exist.collections.triggers.CSVExtractingTrigger">
 *
 *           <parameter name="separator" value="|"/>
 *
 *           <parameter name="path">
 *               <xpath>/content/properties/value[@key eq "product_model"]</xpath>
 *               <extract index="0" element-name="product_name"/>
 *               <extract index="1" element-name="product_code"/>
 *           </parameter>
 *
 *       </trigger>
 *   </triggers>
 * </collection>
 *
 * Currently the configuration of this trigger only supports basic attribute predicates or a name eq value syntax.
 *
 *
 * So for example, when storing a Document with content like the following -
 *
 * <content>
 *      <properties>
 *          <value key="product_model">SomeName|SomeCode12345</value>
 *      </properties>
 * </content>
 *
 * The document will be translated at insertion time into -
 *
 * <content>
 *      <properties>
 *          <value key="product_model">
 *              <product_name>SomeName</product_name>
 *              <product_code>SomeCode12345</product_code>
 *          </value>
 *      </properties>
 * </content>
 *
 * 
 *
 * @author Adam Retter <adam@exist-db.org>
 */
public class CSVExtractingTrigger extends FilteringTrigger {

    //the separator characted for CSV files
    private String separator;

    //key is the xpath to extract for, and value is the extractions to make from the value at that path
    private Map<String, Extraction> extractions = new HashMap<String, Extraction>();

    //the current node path of the SAX stream
    private NodePath currentNodePath = new NodePath();

    private boolean capture = false; //flag to indicate whether to buffer character data for extraction of csv values
    private StringBuilder charactersBuf = new StringBuilder(); //buffer for character data, which will then be parsed to extract csv values


    @Override
    public void configure(DBBroker broker, Collection parent, Map<String, List<?>> parameters) throws CollectionConfigurationException {
        super.configure(broker, parent, parameters);

        //get the separator
        List<String> separators = (List<String>)parameters.get("separator");
        if(separators == null || separators.size() != 1) {
            throw new CollectionConfigurationException("A separator parameter must be provided to the CSVExtractingTrigger configuration");
        } else {
            this.separator = separators.get(0);
        }

        //get the extractions
        List<Map<String, List>> paths = (List<Map<String, List>>)parameters.get("path");
        for(Map<String, List> path : paths){
            List<String> xpaths = path.get("xpath");
            if(xpaths != null && xpaths.size() == 1) {
                String xpath = xpaths.get(0);

                //split out the path and preficate (if present) from the xpath
                String pathExpr;
                String attrPredicate = null;
                if(xpath.indexOf("[") > -1) {
                    pathExpr = xpath.substring(0, xpath.indexOf("["));
                    if(xpath.indexOf("[@") > -1) {
                        attrPredicate = xpath.substring(xpath.indexOf("[@")+2, xpath.indexOf("]"));
                    }
                } else {
                    pathExpr = xpath;
                }

                Extraction extraction = extractions.get(pathExpr);
                if(extraction == null) {
                    extraction = new Extraction();
                    if(attrPredicate != null) {
                        String attrNameValueMatch[] = attrPredicate.split(" eq ");
                        extraction.setMatchAttribute(attrNameValueMatch[0], attrNameValueMatch[1]);
                    }
                }

                List<Properties> extracts = path.get("extract");
                if(extracts != null) {
                    for(Properties extract : extracts) {
                        ExtractEntry extractEntry = new ExtractEntry(Integer.parseInt(extract.getProperty("index")), extract.getProperty("element-name"));
                        extraction.getExtractEntries().add(extractEntry);
                    }
                }
                Collections.sort(extraction.getExtractEntries()); //pre sort
                extractions.put(pathExpr, extraction);
            }
        }
    }

    @Override
    public void startElement(String namespaceURI, String localName, String qname, Attributes attributes) throws SAXException {
        //skips nested elements or already extracted nodes (i.e. during update events)
        //TODO needs through testing during update phase
        if(capture == true) {
            capture = false;
            charactersBuf.delete(0, charactersBuf.length());
        }

        super.startElement(namespaceURI, localName, qname, attributes);
        currentNodePath.add(namespaceURI, localName);

        Extraction extraction = extractions.get(currentNodePath.toLocalPath());
        if(extraction != null)
        {
            //do we have to match an attribute predicate from the xpath in the trigger config?
            if(extraction.mustMatchAttribute()){
                //yes - so try and match
                for(int i = 0; i < attributes.getLength(); i++){
                    if(extraction.matchesAttribute(attributes.getLocalName(i), attributes.getValue(i))){
                        //matched the predicate, so staty capturing
                        capture = true;
                        break;
                    }
                }
            } else {
                //no, so start capturing
                capture = true;
            }
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        if(capture){
            charactersBuf.append(ch, start, length);
        } else {
            super.characters(ch, start, length);
        }
    }

    @Override
    public void endElement(String namespaceURI, String localName, String qname) throws SAXException {
        if(capture) {
            extractCSVValuesToElements();

            capture = false;
            charactersBuf.delete(0, charactersBuf.length());
        }

        super.endElement(namespaceURI, localName, qname);
        currentNodePath.removeLast();
    }

    private void extractCSVValuesToElements() throws SAXException {

        //split the csv values
        String seperatedValues[] = charactersBuf.toString().split(getEscapedSeparatorForRegExp());

        //get the extractions for the current path
        Extraction extraction = extractions.get(currentNodePath.toLocalPath());
        for(ExtractEntry extractEntry : extraction.getExtractEntries()) {

            //extract the value by index
            int index = extractEntry.getIndex();
            if(index < seperatedValues.length) {
                char seperatedValue[] = seperatedValues[index].toCharArray();

                //create a new element for the extracted value
                String localName = extractEntry.getElementName();
                
                super.startElement(XMLConstants.NULL_NS_URI, localName, localName, new EmptyAttributes());
                
                super.characters(seperatedValue, 0, seperatedValue.length);

                super.endElement(XMLConstants.NULL_NS_URI, localName, localName);
            }
        }
    }

    private String getEscapedSeparatorForRegExp() {
        if(separator.length() == 1) {
            //escape the separator character if it is a java regexp character
            if(separator.equals("|") || separator.equals(",") || separator.equals("$") || separator.equals("^")) {
                return "\\" + separator;
            }
        }

        return separator;
    }

    @Override
    public void prepare(int event, DBBroker broker, Txn transaction, XmldbURI documentPath, DocumentImpl existingDocument) throws TriggerException {
    }

    @Override
    public void finish(int event, DBBroker broker, Txn transaction, XmldbURI documentPath, DocumentImpl document) {
    }

    private class NodePath {
        private Stack<QName> pathSegments = new Stack<QName>();

        public void add(String namespaceUri, String localName) {
            pathSegments.push(new QName(namespaceUri, localName));
        }

        public void removeLast() {
            pathSegments.pop();
        }

        public int length() {
            return pathSegments.size();
        }

        //TODO replace with qname path once we understand how to pass in qnames in the xpath parameter to the trigger
        public String toLocalPath() {
            StringBuilder localPath = new StringBuilder();
            localPath.append("/");
            for(int i = 0; i < pathSegments.size(); i++) {
                localPath.append(pathSegments.get(i).getLocalPart());
                if(i + 1 < pathSegments.size()) {
                    localPath.append("/");
                }
            }

            return localPath.toString();
        }
    }

    /*** configuration data classes ***/
    private class Extraction {

        private List<ExtractEntry> extractEntries = new ArrayList<ExtractEntry>();

        private String matchAttrName;
        private String matchAttrValue;

        public List<ExtractEntry> getExtractEntries() {
            return extractEntries;
        }

        public void setMatchAttribute(String attrName, String attrValue) {
            this.matchAttrName = attrName.trim();
            this.matchAttrValue = attrValue.replaceAll("\"", "").trim();
        }

        public boolean mustMatchAttribute() {
            return(this.matchAttrName != null && this.matchAttrValue != null);
        }

        public boolean matchesAttribute(String attrName, String attrValue) {

            //if there is no matching then return true
            if(!mustMatchAttribute()) {
                return true;
            }
            else {
                return this.matchAttrName.equals(attrName) && this.matchAttrValue.equals(attrValue);
            }
        }
    }

    private class ExtractEntry implements Comparable<ExtractEntry> {
        private final int index;
        private final String elementName;

        public ExtractEntry(int index, String elementName) {
            this.index = index;
            this.elementName = elementName;
        }

        public int getIndex() {
            return index;
        }

        public String getElementName() {
            return elementName;
        }

        @Override
        public int compareTo(ExtractEntry other) {
            if(other == null) {
                return -1;
            } else {
                return other.getIndex() - this.getIndex();
            }
        }
    }

    private class EmptyAttributes implements Attributes {

        @Override
        public int getLength() {
            return 0;
        }

        @Override
        public String getURI(int index) {
            return null;
        }

        @Override
        public String getLocalName(int index) {
            return null;
        }

        @Override
        public String getQName(int index) {
            return null;
        }

        @Override
        public String getType(int index) {
            return null;
        }

        @Override
        public String getValue(int index) {
            return null;
        }

        @Override
        public int getIndex(String uri, String localName) {
            return -1;
        }

        @Override
        public int getIndex(String qName) {
            return -1;
        }

        @Override
        public String getType(String uri, String localName) {
            return null;
        }

        @Override
        public String getType(String qName) {
            return null;
        }

        @Override
        public String getValue(String uri, String localName) {
            return null;
        }

        @Override
        public String getValue(String qName) {
            return null;
        }

    }
}