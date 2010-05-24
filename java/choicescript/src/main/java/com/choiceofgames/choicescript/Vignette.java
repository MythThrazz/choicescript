package com.choiceofgames.choicescript;

import static com.choiceofgames.choicescript.XmlHelper.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class Vignette implements IVignette {
	
	
	public Vignette(String name, IInputOutput io, INavigator nav,
			Document vignetteXml, Map<String, Object> stats) {
		this(name, io, nav, vignetteXml, stats, new HashMap<String, Object>());
	}
	
	public Vignette(String name, IInputOutput io, INavigator nav,
			Document vignetteXml, Map<String, Object> stats, Map<String, Object> temps) {
		this.name = name;
		this.io = io;
		this.nav = nav;
		this.vignetteXml = vignetteXml;
		this.stats = stats;
		this.temps = temps;
		this.stats.put("scene", this);
		currentElement = getFirstChildElement(vignetteXml.getDocumentElement());
	}

	final String name;
	final IInputOutput io;
	final INavigator nav;
	final Map<String, Object> stats;
	final ExpressionEvaluator ee = new ExpressionEvaluator(new VariableMap());
	private static final XPath xpath = XPathFactory.newInstance().newXPath();
	Map<String, Object> temps;
	boolean debugMode;
	boolean finished;
	boolean resuming;
	final Document vignetteXml;
	Element currentElement;
	enum Undefined { UNDEFINED };

	@Override
	public void execute() {
		while(currentElement != null) {
			//System.out.println("* " + currentElement);
			String tagName = currentElement.getTagName();
			if ("p".equals(tagName)) {
				io.print(currentElement.getTextContent());
			} else if ("paragraph-break".equals(tagName)) {
				io.paragraphBreak();
			} else if ("line-break".equals(tagName)) {
				io.lineBreak();
			} else if ("choice".equals(tagName)) {
				choice();
			} else if ("finish".equals(tagName)) {
				finish();
			} else if ("switch".equals(tagName)) {
				switchTag();
			} else if ("set".equals(tagName)) {
				set();
			} else if ("set-ref".equals(tagName)) {
				setRef();
			} else if ("goto-ref".equals(tagName)) {
				gotoRef();
			} else if ("print".equals(tagName)) {
				print();
			} else if ("temp".equals(tagName)) {
				temps.put(currentElement.getAttribute("variable"), Undefined.UNDEFINED);
			} else if ("create".equals(tagName)) {
				stats.put(currentElement.getAttribute("variable"), Undefined.UNDEFINED);
			} else if ("input-text".equals(tagName)) {
				io.inputText(currentElement.getAttribute("variable"));
				finished = true;
			} else if ("page-break".equals(tagName)) {
				pageBreak();
				finished = true;
			} else if ("random".equals(tagName)) {
				rand();
			} else if ("goto-scene".equals(tagName)) {
				gotoScene();
			} else if ("ending".equals(tagName)) {
				io.ending();
				finished = true;
			} else if ("stat-chart".equals(tagName)) {
				statChart();
			} else if ("label".equals(tagName)) {
			} else if ("include".equals(tagName)) {
				include();
			} else {
				throw new RuntimeException("Unknown element name: " + tagName);
			}
			if (finished) break;
			if (resuming) {
				resuming = false;
			} else {
				currentElement = getNextElement(currentElement);
			}
		}
		if (!finished) {
			io.finish("Next Chapter");
		}
	}

	private void include() {
		String labelId = currentElement.getAttribute("label");
		Element tag = vignetteXml.getElementById(labelId);
		if (tag == null) throw new RuntimeException("Invalid label; possibly no ID attlist? " + labelId);
		currentElement = tag;
	}


	private void statChart() {
		List<StatChartRow> rows = new ArrayList<StatChartRow>();
		List<Element> rowTags = getChildElements(currentElement);
		for (Element rowTag : rowTags) {
			String name = rowTag.getTagName();
			String varName = rowTag.getAttribute("variable");
			String value = getVariable(varName).toString();
			if ("opposed-pair".equals(name)) {
				Element leftTag = getFirstChildElement(rowTag);
				Element rightTag = getNextSiblingElement(leftTag);
				StatChartRow.Label left, right;
				left = new StatChartRow.Label(getAttribute(leftTag, "text"), getAttribute(leftTag, "definition"));
				right = new StatChartRow.Label(getAttribute(rightTag, "text"), getAttribute(rightTag, "definition"));
				StatChartRow row = new StatChartRow(name, value, left, right);
				rows.add(row);
			} else {
				String definition = getAttribute(rowTag, "definition");
				StatChartRow.Label label;
				label = new StatChartRow.Label(getAttribute(rowTag, "label"), definition);
				StatChartRow row = new StatChartRow(name, value, label);
				rows.add(row);
			}
		}
		io.printStatChart(rows);
	}
	
	private void rand() {
		String variableName = currentElement.getAttribute("variable");
		List<Element> children = getChildElements(currentElement);
		Element minExpression = getFirstChildElement(children.get(0));
		Number min = (Number) evaluateExpression(minExpression);
		Element maxExpression = getFirstChildElement(children.get(1));
		Number max = (Number) evaluateExpression(maxExpression);
		Number value;
		if (min instanceof Integer && max instanceof Integer) {
			value = randomInt(min.intValue(), max.intValue());
		} else {
			value = randomDouble(min.doubleValue(), max.doubleValue());
		}
		setVariable(variableName, value);
	}
	
	private int randomInt(int min, int max) {
		int diff = max - min;
		double random = Math.random();
		return min + (int)Math.floor(random * diff + 1);
	}
	
	private double randomDouble(double min, double max) {
		double diff = max - min;
		double random = Math.random();
		return min + random * diff;
	}
	
	private void gotoScene() {
		finished = true;
		String name = currentElement.getAttribute("name");
		Document doc = io.loadDocument(name);
		IVignette vig = new Vignette(name, io, nav, doc, stats);
		vig.execute();
	}
	
	private void print() {
		Element tag = getFirstChildElement(currentElement);
		Object value = evaluateExpression(tag);
		io.print(value.toString());
	}
	
	private void gotoRef() {
		Element tag = getFirstChildElement(currentElement);
		String labelId = evaluateExpression(tag).toString();
		currentElement = vignetteXml.getElementById(labelId);
	}
	
	private void setRef() {
		List<Element> children = getChildElements(currentElement);
		Element nameExpression = getFirstChildElement(children.get(0));
		String variableName = evaluateStringExpression(nameExpression);
		Element valueExpression = getFirstChildElement(children.get(1));
		Object value = evaluateExpression(valueExpression);
		setVariable(variableName, value);
	}
	
	private void set() {
		String variableName = currentElement.getAttribute("variable");
		Element expressionElement = getFirstChildElement(currentElement);
		Object value = evaluateExpression(expressionElement);
		setVariable(variableName, value);
	}

	private void setVariable(String variableName, Object value) {
		if (temps.containsKey(variableName)) {
			temps.put(variableName, value);
		} else if (stats.containsKey(variableName)) {
			stats.put(variableName, value);
		} else {
			throw new RuntimeException("Undeclared variable: " + variableName);
		}
	}

	private Element getNextElement(Element tag) {
		Element newTag = getNextSiblingElement(tag);
		if (newTag != null) return newTag;
		Element parent = (Element) tag.getParentNode();
		newTag = findValidParent(parent);
		if (newTag != null) return getNextElement(newTag);
		return null;
	}
	
	private Element findValidParent(Element tag) {
		if (vignetteXml.getDocumentElement().isSameNode(tag)) {
			return null;
		}
		Element parent = (Element) tag.getParentNode();
		String tagName = parent.getTagName();
		if (tagName.matches("(vignette|option|result|else)")) {
			// What about multi-options?
			return tag;
		}
		return findValidParent(parent);
	}



	
	private void switchTag() {
		List<Element> ifTags = getChildElementsByName(currentElement, "if");
		for (Element ifTag : ifTags) {
			List<Element> ifChildren = getChildElements(ifTag);
			Element test = getFirstChildElement(ifChildren.get(0));
			if (evaluateBooleanExpression(test)) {
				currentElement = getFirstChildElement(ifChildren.get(1));
				resuming = true;
				return;
			}
		}
		List<Element> elseTag = getChildElementsByName(currentElement, "else"); 
		if (elseTag.isEmpty()) return;
		currentElement = getFirstChildElement(elseTag.get(0));
		resuming = true;
	}
	
	private boolean evaluateBooleanExpression(Element tag) {
		Object o = evaluateExpression(tag);
		return (Boolean) o;
	}
	
	private String evaluateStringExpression(Element tag) {
		Object o = evaluateExpression(tag);
		return (String) o;
	}
	
	private Object evaluateExpression(Element tag) {
		return ee.evaluate(tag);
	}
			
	public class VariableMap {	
		public Object get(String name) {
			return getVariable(name);
		}
	}
	
	private Object getVariable(String name) {
		if ("true".equals(name)) return true;
		if ("false".equals(name)) return false;
		Object value = temps.get(name);
		if (value != null) {
			if (value == Undefined.UNDEFINED) {
				throw new RuntimeException("Temporary variable defined but has no value: " + name);
			}
			return value;
		}
		value = stats.get(name);
		if (value == null) throw new RuntimeException("Unset variable" + name);
		return value;
	}
	
	private void pageBreak() {
		// TODO if screen empty, skip it...
		String promptMessage = currentElement.getAttribute("text");
		if (promptMessage == null || promptMessage.isEmpty()) promptMessage = "Next";
		io.pageBreak(promptMessage);
		Element resume = getNextSiblingElement(currentElement);
		io.saveState(this.name, stats, temps, getResumePoint(resume));
		finished = true;
	}


	private void finish() {
		String promptMessage = currentElement.getAttribute("text");
		if (promptMessage == null || promptMessage.isEmpty()) promptMessage = "Next Chapter";
		io.finish(promptMessage);
		finished = true;
	}

	private void choice() {
		List<OptionDisplayGroup> odg = parseChoice(currentElement);
		io.choice(odg);
		io.saveState(this.name, stats, temps, getResumePoint(currentElement));
		finished = true;
	}
	
	private List<OptionDisplayGroup> parseChoice(Element tag) {
		List<OptionDisplayGroup> odgs = new ArrayList<OptionDisplayGroup>();
		// TODO multichoice
		String groupName = "choice";
		List<Element> optionTags = XmlHelper.getChildElements(tag);		
		List<String> optionTitles = new ArrayList<String>();
		for (Element optionTag : optionTags) {
			if ("if".equals(optionTag.getTagName())) {
				List<Element> ifChildren = getChildElements(optionTag);
				Element test = getFirstChildElement(ifChildren.get(0));
				if (evaluateBooleanExpression(test)) {
					optionTag = ifChildren.get(1);
				} else {
					continue;
				}
			}
			
			String name = optionTag.getAttribute("text");
			optionTitles.add(name);
			
		}
		odgs.add(new OptionDisplayGroup(groupName, optionTitles));
		return odgs;
	}
	
	
	
		
	@Override
	public void inputText(String variable, String text) {
		setVariable(variable, text);
		currentElement = getNextElement(currentElement);
	}

	@Override
	public boolean isDebugMode() {
		return debugMode;
	}

	@Override
	public void resolveChoice(List<Integer> selections) {
		for (Iterator<Integer> it = selections.iterator(); it.hasNext();) {
			int selection = it.next();
			List<Element> options = getChildElements(currentElement);
			for (int i = 0; i < options.size(); i++) {
				Element optionTag = options.get(i);
				if ("if".equals(optionTag.getTagName())) {
					List<Element> ifChildren = getChildElements(optionTag);
					Element test = ifChildren.get(0);
					if (evaluateBooleanExpression(getFirstChildElement(test))) {
						options.set(i, ifChildren.get(1));
						i--;
					} else {
						options.remove(i);
						i--;
					}
				}
			}
			
			Element parent = options.get(selection);
			List<Element> children = getChildElements(parent);
			
			if (children.isEmpty() && it.hasNext()) {
				throw new RuntimeException("Bug, expected suboptions but option element was empty");
			} else if (children.isEmpty()) {
				do {
					currentElement = (Element) parent.getParentNode();
				} while (!currentElement.getTagName().equals("choice"));
				currentElement = getNextElement(currentElement);
				
			} else {
				currentElement = children.get(0);
			}
		}
	}

	@Override
	public void setDebugMode(boolean debugMode) {
		this.debugMode = debugMode;
	}

	@Override
	public void setResumePoint(String resumePoint) {
		try {
			currentElement = (Element) xpath.evaluate(resumePoint, vignetteXml, XPathConstants.NODE); 
		} catch (XPathExpressionException e) {
			throw new RuntimeException("Bug", e);
		}
	}
	

}