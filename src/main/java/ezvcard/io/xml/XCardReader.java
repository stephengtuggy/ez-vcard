package ezvcard.io.xml;

import static ezvcard.io.xml.XCardQNames.GROUP;
import static ezvcard.io.xml.XCardQNames.PARAMETERS;
import static ezvcard.io.xml.XCardQNames.VCARD;
import static ezvcard.io.xml.XCardQNames.VCARDS;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.namespace.QName;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import ezvcard.VCard;
import ezvcard.VCardVersion;
import ezvcard.io.CannotParseException;
import ezvcard.io.EmbeddedVCardException;
import ezvcard.io.ParseWarnings;
import ezvcard.io.SkipMeException;
import ezvcard.io.scribe.ScribeIndex;
import ezvcard.io.scribe.VCardPropertyScribe;
import ezvcard.io.scribe.VCardPropertyScribe.Result;
import ezvcard.parameter.VCardParameters;
import ezvcard.property.VCardProperty;
import ezvcard.property.Xml;
import ezvcard.util.XmlUtils;

/*
 Copyright (c) 2013, Michael Angstadt
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are met: 

 1. Redistributions of source code must retain the above copyright notice, this
 list of conditions and the following disclaimer. 
 2. Redistributions in binary form must reproduce the above copyright notice,
 this list of conditions and the following disclaimer in the documentation
 and/or other materials provided with the distribution. 

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 The views and conclusions contained in the software and documentation are those
 of the authors and should not be interpreted as representing official policies, 
 either expressed or implied, of the FreeBSD Project.
 */

/**
 * <p>
 * Reads xCards (XML-encoded vCards) in a streaming fashion.
 * </p>
 * <p>
 * <b>Example:</b>
 * 
 * <pre class="brush:java">
 * File file = new File("vcards.xml");
 * final List&lt;VCard&gt; vcards = new ArrayList&lt;VCard&gt;();
 * XCardReader xcardReader = new XCardReader(file);
 * xcardReader.read(new XCardReadListener(){
 *   public void vcardRead(VCard vcard, List&lt;String&gt; warnings) throws StopReadingException{
 *     vcards.add(vcard);
 *     //throw a "StopReadingException" to stop parsing early
 *   }
 * }
 * </pre>
 * 
 * </p>
 * @author Michael Angstadt
 * @see <a href="http://tools.ietf.org/html/rfc6351">RFC 6351</a>
 */
public class XCardReader implements Closeable {
	private final VCardVersion version = VCardVersion.V4_0;
	private final String NS = version.getXmlNamespace();

	private final Source source;
	private final Closeable stream;
	private final ParseWarnings warnings = new ParseWarnings();
	private ScribeIndex index = new ScribeIndex();
	private XCardListener listener;

	/**
	 * Creates an xCard reader.
	 * @param str the string to read the xCards from
	 */
	public XCardReader(String str) {
		this(new StringReader(str));
	}

	/**
	 * Creates an xCard reader.
	 * @param in the input stream to read the xCards from
	 */
	public XCardReader(InputStream in) {
		source = new StreamSource(in);
		stream = in;
	}

	/**
	 * Creates an xCard reader.
	 * @param file the file to read the xCards from
	 * @throws FileNotFoundException if the file doesn't exist
	 */
	public XCardReader(File file) throws FileNotFoundException {
		this(new FileInputStream(file));
	}

	/**
	 * Creates an xCard reader.
	 * @param reader the reader to read from
	 */
	public XCardReader(Reader reader) {
		source = new StreamSource(reader);
		stream = reader;
	}

	/**
	 * Creates an xCard reader.
	 * @param node the DOM node to read from
	 */
	public XCardReader(Node node) {
		source = new DOMSource(node);
		stream = null;
	}

	/**
	 * <p>
	 * Registers a property scribe. This is the same as calling:
	 * </p>
	 * <p>
	 * {@code getScribeIndex().register(scribe)}
	 * </p>
	 * @param scribe the scribe to register
	 */
	public void registerScribe(VCardPropertyScribe<? extends VCardProperty> scribe) {
		index.register(scribe);
	}

	/**
	 * Gets the scribe index.
	 * @return the scribe index
	 */
	public ScribeIndex getScribeIndex() {
		return index;
	}

	/**
	 * Sets the scribe index.
	 * @param index the scribe index
	 */
	public void setScribeIndex(ScribeIndex index) {
		this.index = index;
	}

	/**
	 * Starts parsing the XML document. This method blocks until the entire
	 * input stream or DOM is consumed, or until a {@link StopReadingException}
	 * is thrown from the given {@link XCardListener}.
	 * @param listener used for retrieving the parsed vCards
	 * @throws TransformerException if there's a problem reading from the input
	 * stream or a problem parsing the XML
	 */
	public void read(XCardListener listener) throws TransformerException {
		this.listener = listener;

		//create the transformer
		Transformer transformer;
		try {
			transformer = TransformerFactory.newInstance().newTransformer();
		} catch (TransformerConfigurationException e) {
			//no complex configurations
			throw new RuntimeException(e);
		} catch (TransformerFactoryConfigurationError e) {
			//no complex configurations
			throw new RuntimeException(e);
		}

		//prevent error messages from being printed to stderr
		transformer.setErrorListener(new ErrorListener() {
			public void error(TransformerException e) {
				//empty
			}

			public void fatalError(TransformerException e) {
				//empty
			}

			public void warning(TransformerException e) {
				//empty
			}
		});

		//start parsing
		ContentHandlerImpl handler = new ContentHandlerImpl();
		SAXResult result = new SAXResult(handler);
		try {
			transformer.transform(source, result);
		} catch (TransformerException e) {
			Throwable cause = e.getCause();
			if (cause != null && cause instanceof StopReadingException) {
				//ignore StopReadingException because it signals that the user canceled the parsing operation
			} else {
				throw e;
			}
		}
	}

	private class ContentHandlerImpl extends DefaultHandler {
		private final Document DOC = XmlUtils.createDocument();
		private final List<QName> hierarchy = new ArrayList<QName>();

		private String group;
		private StringBuilder characterBuffer = new StringBuilder();
		private Element propertyElement, parent;
		private QName propertyQName, paramName, paramDataType;

		private VCard vcard;
		private VCardParameters parameters;

		@Override
		public void characters(char[] buffer, int start, int length) throws SAXException {
			characterBuffer.append(buffer, start, length);
		}

		@Override
		public void endElement(String namespace, String localName, String qName) throws SAXException {
			String textContent = characterBuffer.toString();
			characterBuffer.setLength(0);

			if (eq()) {
				//no <vcards> elements were read yet
				return;
			}

			if (eq(VCARDS) && (!namespace.equals(VCARDS.getNamespaceURI()) || !localName.equals(VCARDS.getLocalPart()))) {
				//ignore any non-<vcard> elements under <vcards>
				return;
			}

			QName qname = hierarchy.remove(hierarchy.size() - 1);

			//if inside of <parameters>
			if (((group == null && startsWith(VCARDS, VCARD, propertyQName, PARAMETERS)) || (group != null && startsWith(VCARDS, VCARD, GROUP, propertyQName, PARAMETERS)))) {
				if (qname.equals(paramDataType)) {
					parameters.put(paramName.getLocalPart(), textContent);
					paramDataType = null;
					return;
				}

				if (qname.equals(paramName)) {
					paramName = null;
					return;
				}

				return;
			}

			//</parameters>
			if (((group == null && eq(VCARDS, VCARD, propertyQName)) || (group != null && eq(VCARDS, VCARD, GROUP, propertyQName))) && qname.equals(PARAMETERS)) {
				return;
			}

			//if the property element has ended
			if (((group == null && eq(VCARDS, VCARD)) || (group != null && eq(VCARDS, VCARD, GROUP))) && qname.equals(propertyQName)) {
				propertyElement.appendChild(DOC.createTextNode(textContent));

				String propertyName = localName;
				VCardProperty property;
				VCardPropertyScribe<? extends VCardProperty> scribe = index.getPropertyScribe(qname);
				try {
					Result<? extends VCardProperty> result = scribe.parseXml(propertyElement, parameters);
					property = result.getProperty();
					property.setGroup(group);
					vcard.addProperty(property);
					for (String warning : result.getWarnings()) {
						warnings.add(null, propertyName, warning);
					}
				} catch (SkipMeException e) {
					warnings.add(null, propertyName, 22, e.getMessage());
				} catch (CannotParseException e) {
					String xml = XmlUtils.toString(propertyElement);
					warnings.add(null, propertyName, 33, xml, e.getMessage());

					scribe = index.getPropertyScribe(Xml.class);
					Result<? extends VCardProperty> result = scribe.parseXml(propertyElement, parameters);
					property = result.getProperty();
					property.setGroup(group);
					vcard.addProperty(property);
				} catch (EmbeddedVCardException e) {
					warnings.add(null, propertyName, 34);
				}

				propertyElement = null;
				return;
			}

			//if inside of the property element
			if (propertyElement != null) {
				if (textContent.length() > 0) {
					parent.appendChild(DOC.createTextNode(textContent));
				}
				parent = (Element) parent.getParentNode();
				return;
			}

			//</group>
			if (eq(VCARDS, VCARD) && qname.equals(GROUP)) {
				group = null;
				return;
			}

			//</vcard>
			if (eq(VCARDS) && qname.equals(VCARD)) {
				listener.vcardRead(vcard, warnings.copy());
				warnings.clear();
				vcard = null;
				return;
			}
		}

		@Override
		public void startElement(String namespace, String localName, String qName, Attributes attributes) throws SAXException {
			QName qname = new QName(namespace, localName);
			String textContent = characterBuffer.toString();
			characterBuffer.setLength(0);

			if (eq()) {
				//<vcards>
				if (VCARDS.equals(qname)) {
					hierarchy.add(qname);
				}
				return;
			}

			if (eq(VCARDS)) {
				//<vcard>
				if (VCARD.equals(qname)) {
					vcard = new VCard();
					vcard.setVersion(version);
					hierarchy.add(qname);
				}
				return;
			}

			hierarchy.add(qname);

			//<group>
			if (eq(VCARDS, VCARD, GROUP)) {
				group = attributes.getValue("name");
				return;
			}

			//start property element
			if (propertyElement == null) {
				propertyElement = createElement(namespace, localName, attributes);
				propertyQName = qname;
				parameters = new VCardParameters();
				parent = propertyElement;
				return;
			}

			//<parameters>
			if ((group == null && eq(VCARDS, VCARD, propertyQName, PARAMETERS)) || (group != null && eq(VCARDS, VCARD, GROUP, propertyQName, PARAMETERS))) {
				return;
			}

			//inside of <parameters>
			if ((group == null && startsWith(VCARDS, VCARD, propertyQName, PARAMETERS)) || (group != null && startsWith(VCARDS, VCARD, GROUP, propertyQName, PARAMETERS))) {
				if (NS.equals(namespace)) {
					if (endsWith(paramName, qname)) {
						paramDataType = qname;
					} else {
						paramName = qname;
					}
				}

				return;
			}

			//append to property element
			if (textContent.length() > 0) {
				parent.appendChild(DOC.createTextNode(textContent));
			}
			Element element = createElement(namespace, localName, attributes);
			parent.appendChild(element);
			parent = element;
		}

		private Element createElement(String namespace, String localName, Attributes attributes) {
			Element element = DOC.createElementNS(namespace, localName);
			for (int i = 0; i < attributes.getLength(); i++) {
				String qname = attributes.getQName(i);
				if (qname.startsWith("xmlns:")) {
					continue;
				}

				String name = attributes.getLocalName(i);
				String value = attributes.getValue(i);
				element.setAttribute(name, value);
			}
			return element;
		}

		private boolean eq(QName... elements) {
			return hierarchy.equals(Arrays.asList(elements));
		}

		private boolean startsWith(QName... elements) {
			if (elements.length > hierarchy.size()) {
				return false;
			}

			return hierarchy.subList(0, elements.length).equals(Arrays.asList(elements));
		}

		private boolean endsWith(QName... elements) {
			if (elements.length > hierarchy.size()) {
				return false;
			}

			return hierarchy.subList(hierarchy.size() - elements.length, hierarchy.size()).equals(Arrays.asList(elements));
		}
	}

	/**
	 * Closes the underlying input stream.
	 */
	public void close() throws IOException {
		if (stream != null) {
			stream.close();
		}
	}
}