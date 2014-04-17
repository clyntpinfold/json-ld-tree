package daverog.jsonld.tree;

import com.google.common.collect.Lists;
import com.hp.hpl.jena.rdf.model.*;

import java.util.Collections;
import java.util.List;


public class RdfTree implements Comparable<RdfTree> {

	public static final String DEFAULT_RESULT_ONTOLOGY_URI_PREFIX = "http://purl.org/ontology/rdf-result/";
	public static final String RDF_PREFIX = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
	public static final String RDF_TYPE = RDF_PREFIX + "type";
	public static final String OWL_PREFIX = "http://www.w3.org/2002/07/owl#";

	private final RdfTree parent;
	private List<RdfTree> children = Lists.newArrayList();

	private final boolean inverse;
	private final boolean list;
	private final RDFNode node;
	private final Property predicate;
	private final Model model;
	private final NameResolver nameResolver;
	private Resource type;

	public RdfTree(Model model, NameResolver nameResolver, RdfTree parent, RDFNode node, Property predicate, boolean inverse) {
		this.model = model;
		this.nameResolver = nameResolver;
		this.parent = parent;
		this.node = node;
		this.predicate = predicate;
		this.inverse = inverse;
		list = false;
	}

	public RdfTree(Model model, NameResolver nameResolver, RDFNode rootNode) {
		this.model = model;
		this.nameResolver = nameResolver;
		this.node = rootNode;
		list = false;
		predicate = null;
		inverse = false;
		parent = null;
	}

	public RdfTree(Model model, NameResolver nameResolver) {
		this.model = model;
		this.nameResolver = nameResolver;
		list = true;
		predicate = null;
		inverse = false;
		parent = null;
		node = null;
	}

    public void addChild(RdfTree child) {
        children.add(child);
    }

	public void addListItem(Resource listItem) {
		children.add(new RdfTree(model, nameResolver, this, listItem, null, false));
	}

	public Property getPredicate() {
		return predicate;
	}

	public RDFNode getNode() {
		return node;
	}

	public List<RdfTree> getChildren() {
		return children;
	}

	public DirectionalPredicate getDirectionalPredicate() {
		return new DirectionalPredicate(getPredicate(), inverse);
	}

	public void setType(Resource type) {
		this.type = type;
	}

	public boolean hasParentWithNode(RDFNode node) {
		if (parent == null) return false;
		if (parent.getNode() == null) return false;
		if (parent.getNode().equals(node)) return true;
		return parent.hasParentWithNode(node);
	}

	public boolean hasListRootWithNode(RDFNode node) {
		if (parent == null) return false;
		if (parent.isList()) return parent.hasListItemWithNode(node);
		return parent.hasListRootWithNode(node);
	}

	private boolean hasListItemWithNode(RDFNode node) {
		if (!list) return false;
		for (RdfTree childTree: children) {
			if (childTree.getNode().equals(node)) return true;
		}
		return false;
	}

	public void canonicalise() {
		for (RdfTree childTree: children) {
			childTree.canonicalise();
		}
		if (!list) Collections.sort(children);
	}

	private boolean isLiteral() {
		return getNode().isLiteral();
	}

	public boolean isType() {
		return getPredicate() != null && getPredicate().getURI().equals(RDF_TYPE) && !inverse;
	}

	public boolean isInverse() {
		return inverse;
	}

	public Resource getType() {
		return type;
	}

	@Override
	public int compareTo(RdfTree tree) {
		//Rules to ensure a predictable ordering of children of a tree
		if (isType() && tree.isType()) return 0;
		if (isType() && !tree.isType()) return -1;
		if (!isType() && tree.isType()) return 1;

		if (inverse && !tree.inverse) return 1;
		if (!inverse && tree.inverse) return -1;

		if (isLiteral() && !tree.isLiteral()) return -1;
		if (!isLiteral() && tree.isLiteral()) return 1;

		if (isChildlessResource() && !tree.isChildlessResource()) return -1;
		if (!isChildlessResource() && tree.isChildlessResource()) return 1;

		if (getPredicate().equals(tree.getPredicate())) {
			if (isLiteral() && tree.isLiteral())
				return RdfTreeUtils.compareObjects(
						getNode().asLiteral().getValue(),
						tree.getNode().asLiteral().getValue());
			return RdfTreeUtils.compareObjects(
					getNode(),
					tree.getNode());
		}

		return nameResolver.compareNames(getPredicate(), tree.getPredicate());
	}

	public String asXml() {
		return new RdfTreeXmlWriter().asXml(this);
	}

	public String asHtml(String relativeUrlBase) {
		return new RdfTreeXmlWriter().asHtml(this, relativeUrlBase);
	}

	public String asJson() {
		return new RdfTreeJsonWriter().asJson(this);
	}

	public boolean isList() {
		return list;
	}

	public NameResolver getNameResolver() {
		return nameResolver;
	}

	public boolean isChildlessResource() {
		return node.isResource() && children.isEmpty();
	}

	public boolean isRoot() {
		return parent == null;
	}

	public boolean isEmpty() {
		return children.isEmpty() && node == null;
	}

    public RdfTree getParent() { return parent; }
}
