package daverog.jsonld.tree;

import com.google.common.base.Function;
import com.google.common.collect.*;
import com.hp.hpl.jena.rdf.model.*;

import java.util.*;

public class RdfTreeGenerator {

    private final String rdfResultOntologyPrefix;

    enum TreeType {
        UNKNOWN,
        ITEM,
        LIST,
        LIST_WITH_ORDER_BY_PREDICATE
    }

    public RdfTreeGenerator() {
        rdfResultOntologyPrefix = RdfTree.DEFAULT_RESULT_ONTOLOGY_URI_PREFIX;
    }

    public RdfTreeGenerator(String rdfResultOntologyPrefix) {
        this.rdfResultOntologyPrefix = rdfResultOntologyPrefix;
    }

    public RdfTree generateRdfTree(Model model) throws RdfTreeException {
        return generateRdfTree(model, Lists.<String>newArrayList(), Maps.<String, String>newHashMap());
    }

    public RdfTree generateRdfTree(Model model, Map<String, String> nameOverrides) throws RdfTreeException {
        return generateRdfTree(model, Lists.<String>newArrayList(), nameOverrides);
    }

    public RdfTree generateRdfTree(Model model, List<String> prioritisedNamespaces) throws RdfTreeException {
        return generateRdfTree(model, prioritisedNamespaces, Maps.<String, String>newHashMap());
    }

    public RdfTree generateRdfTree(Model model, List<String> prioritisedNamespaces, Map<String, String> nameOverrides) throws RdfTreeException {
        NameResolver nameResolver = new NameResolver(model, prioritisedNamespaces, nameOverrides, rdfResultOntologyPrefix);
        TreeType treeType = TreeType.UNKNOWN;

        if (model.isEmpty())
            return new RdfTree(model, nameResolver, null);

        List<Statement> results = getSomeStatements(model, new SimpleSelector(
                model.getResource(rdfResultOntologyPrefix + "this"),
                null,
                (RDFNode) null),
                "result:this is not present as the subject of a statement, so an RDF tree cannot be generated");
        Statement firstResult = results.get(0);
        Resource orderingPredicate = null;
        boolean sortAscending = true;
        List<Resource> listItems = Lists.newArrayList();
        for (Statement result : results) {
            if (!result.getObject().isResource())
                throw new RdfTreeException("result:this statement contained a non-resource object");
            if (result.getPredicate().getURI().equals(rdfResultOntologyPrefix + "item")) {
                if (results.size() != 1)
                    throw new RdfTreeException("More than one result:this subject was found for a single item result");
                if (treeType == TreeType.UNKNOWN) treeType = TreeType.ITEM;
                else
                    throw new RdfTreeException("Tree type " + treeType + " was identified alongside conflicting predicate result:item");
            }
            if (result.getPredicate().getURI().equals(rdfResultOntologyPrefix + "next")) {
                if (results.size() != 1)
                    throw new RdfTreeException("More than one starting point was found for a list described by result:next");
                if (treeType == TreeType.UNKNOWN) treeType = TreeType.LIST;
                else
                    throw new RdfTreeException("Tree type " + treeType + " was identified alongside conflicting predicate result:next");
            }
            if (result.getPredicate().getURI().equals(rdfResultOntologyPrefix + "listItem")) {
                if (treeType == TreeType.UNKNOWN || treeType == TreeType.LIST_WITH_ORDER_BY_PREDICATE)
                    treeType = TreeType.LIST_WITH_ORDER_BY_PREDICATE;
                else
                    throw new RdfTreeException("Tree type " + treeType + " was identified alongside conflicting predicate result:listItem");
                listItems.add(result.getObject().asResource());
            }
        }
        for (Statement result : results) {
            if (result.getPredicate().getURI().equals(rdfResultOntologyPrefix + "orderByPredicate")) {
                if (orderingPredicate != null)
                    throw new RdfTreeException("More than one ordering predicate was supplied.");
                if (treeType != TreeType.LIST_WITH_ORDER_BY_PREDICATE)
                    throw new RdfTreeException("An ordering predicate was supplied for tree type " + treeType);
                orderingPredicate = result.getObject().asResource();
            }
            if (result.getPredicate().getURI().equals(rdfResultOntologyPrefix + "sortOrder")) {
                if (treeType != TreeType.LIST_WITH_ORDER_BY_PREDICATE)
                    throw new RdfTreeException("An sort order was supplied for tree type " + treeType);
                if (!result.getObject().isResource()) throw new RdfTreeException("An sort order was not a resource");
                Resource sortOrder = result.getObject().asResource();
                if (sortOrder.getURI().equals(rdfResultOntologyPrefix + "AscendingOrder")) {
                    sortAscending = true;
                } else if (sortOrder.getURI().equals(rdfResultOntologyPrefix + "DescendingOrder")) {
                    sortAscending = false;
                } else {
                    throw new RdfTreeException("Unknown sort order: " + result.getObject().asResource().getURI());
                }
            }
        }
        if (treeType == TreeType.ITEM) {
            return buildRdfTree(model, new RdfTree(model, nameResolver, firstResult.getObject()));
        } else if (treeType == TreeType.LIST) {
            return buildRdfList(model, nameResolver);
        } else if (treeType == TreeType.LIST_WITH_ORDER_BY_PREDICATE) {
            listItems = sortListAccordingToOrderingPredicate(listItems, orderingPredicate, sortAscending, model);
            return buildRdfList(model, nameResolver, listItems);
        }

        throw new RdfTreeException("The tree type could not be identified, the necessary result:this statements were not present");
    }

    private List<Resource> sortListAccordingToOrderingPredicate(
            List<Resource> listItems, final Resource orderingPredicate, final boolean sortAscending, final Model model) {
        Collections.sort(listItems, new Comparator<Resource>() {
            public int compare(Resource first, Resource second) {
                List<RDFNode> firstValues = getAllValuesForSubjectAndPredicate(model, first, orderingPredicate);
                List<RDFNode> secondValues = getAllValuesForSubjectAndPredicate(model, second, orderingPredicate);

                // Resources without any values for the ordering predicate are sorted in the same way as sparql would
                // which is that they are treated as having the lowest possible value.
                if (firstValues.isEmpty()) return -1;
                if (secondValues.isEmpty()) return 1;

                return RdfTreeUtils.compareTwoListsOfValues(firstValues, secondValues, new Comparator<RDFNode>() {
                    public int compare(RDFNode node1, RDFNode node2) {
                        //The following describes the ordering preference when sorting
                        //lists of resources by the values of their objects.
                        //
                        //The natural (Java) ordering is used, but with a preference
                        //for types of object (in order of how they would appear in
                        //a list):
                        //
                        //1: Strings
                        //2: Any other literals (ordered by toString if their types differ)
                        //3: Resources (ordered by URI)

                        if (node1.isLiteral() && !node2.isLiteral()) return -1;
                        if (!node1.isLiteral() && node2.isLiteral()) return 1;
                        if (node1.isLiteral() && node2.isLiteral()) {

                            Object value1 = node1.asLiteral().getValue();
                            Object value2 = node2.asLiteral().getValue();

                            if (value1 instanceof String && !(value2 instanceof String)) return -1;
                            if (!(value1 instanceof String) && value2 instanceof String) return 1;

                            return RdfTreeUtils.compareObjects(
                                    node1.asLiteral().getValue(),
                                    node2.asLiteral().getValue());
                        }
                        return RdfTreeUtils.compareObjects(node1, node2);
                    }
                });
            }


        });

        if (!sortAscending) Collections.reverse(listItems);

        return listItems;
    }

    private List<RDFNode> getAllValuesForSubjectAndPredicate(Model model, Resource subject, Resource predicate) {
        Property property = predicate == null ? null : model.getProperty(predicate.getURI());

        List<Statement> statements = model.listStatements(
                new SimpleSelector(
                        subject,
                        property,
                        (RDFNode) null)).toList();

        return Lists.transform(statements, new Function<Statement, RDFNode>() {
            public RDFNode apply(Statement statement) {
                return statement.getObject();
            }
        });
    }

    private List<Resource> generateListItemsUsingResultNext(Model model, Resource firstItem) throws RdfTreeException {
        Statement next = getNoneOrSingleStatement(model, new SimpleSelector(
                firstItem,
                model.getProperty(rdfResultOntologyPrefix + "next"),
                (RDFNode) null), "too many result:next predicates assigned to " + firstItem.toString());

        if (next != null) {
            if (!next.getObject().isResource()) {
                throw new RdfTreeException("result:next cannot be a literal or blank node");
            }

            return Lists.<Resource>asList(firstItem, generateListItemsUsingResultNext(model, next.getObject().asResource()).toArray(new Resource[0]));
        }

        return Lists.newArrayList(firstItem);
    }

    private RdfTree buildRdfTree(Model model, RdfTree root) {
        while (!root.isFullyConstructed()) {
            expandRdfTree(model, root);
        }

        return root;
    }

    public void buildTree(Model model, RdfTree root, NameResolver nameResolver, List<Resource> listitems) {

        // Keep track of the nodes we have seen
        ArrayList<Resource> seen = new ArrayList<Resource>();

        // Queue of nodes to process; this makes this a breadth-first traversal.
        ArrayDeque queue = new ArrayDeque<RdfTree>();
        queue.push(root);

        // As long as there are still potential parents to process, keep going.
        while (!queue.isEmpty()) {

            // Parent node is head of queue
            RdfTree node = (RdfTree)queue.pop();

            // Find immediate children
            List<Statement> subjects = model.listStatements(new SimpleSelector(node.getNode().asResource(), null, (RDFNode) null)).toList();
            List<Statement> objects = model.listStatements(new SimpleSelector(null, null, node.getNode())).toList();
            List<Statement> children = Lists.newArrayList(subjects); children.addAll(objects);
            for (Statement child : children) {

                System.out.println("--> " + child + " " + child.hashCode());

                RDFNode potential = child.getObject();
                if (potential.isResource()) {

                    // Is this an inverse node? i.e. one that points AT the parent.
                    boolean inverse = false;
                    if (potential.equals(node.getNode())) {

                        // If this is a circular triple, discard (S p S)
                        Resource subj = child.getSubject();
                        if (subj.equals(potential.asResource())) {
                            continue;
                        }

                        // Exchange the target child node, and mark as inverse
                        potential = (RDFNode)subj;
                        inverse = true;
                    }

                    // We are considering the node (either S or O)
                    Resource resource = potential.asResource();

                    // Do not add children that are an ancestor of this
                    if (seen.contains(resource)) {
                        continue;
                    }

                    // Add as a child node, and push to queue for processing
                    RdfTree childNode = new RdfTree(model, nameResolver, node, potential, child.getPredicate(), inverse);
                    node.addChild(childNode);

                    // Never continue adding children past existing list nodes. They will appear in the tree, but
                    // as a "pointer". Otherwise, add to queue for later.
                    if (!listitems.contains(resource)) {
                        queue.push(childNode);
                    }
                }
                else {
                    // Is it a literal? Can never be inverse here.
                    RdfTree childNode = new RdfTree(model, nameResolver, node, potential, child.getPredicate(), false);
                    node.addChild(childNode);
                }
            }

            // We have now seen this node.
            if (node.getNode().isResource()) {
                seen.add(node.getNode().asResource());
            }
        }
    }

    private RdfTree buildRdfList(Model model, NameResolver nameResolver) throws RdfTreeException {
        // Extract the list roots
        List<Statement> results =
                getSomeStatements(model, new SimpleSelector(
                                model.getResource(rdfResultOntologyPrefix + "this"),
                                null,
                                (RDFNode) null),
                        "result:this is not present as the subject of a statement, so an RDF tree cannot be generated"
                );

        Statement firstResult = results.get(0);

        // List roots are the SUBJECTs of the triples in the reference data list
        List<Resource> listRoots = generateListItemsUsingResultNext(model, firstResult.getObject().asResource());

        // Remove any rdfresult triples; we have all we need.
        StmtIterator refdata = model.listStatements(new SimpleSelector() {
            @Override public boolean selects(Statement s) {
                return s.getPredicate().getNameSpace().toString().equals(rdfResultOntologyPrefix);
            }
        });

        Model modelNoRefData = model.remove(refdata.toList());

        // Create the single root node
        RdfTree list = new RdfTree(modelNoRefData, nameResolver);

        // Build a tree for each list item, and add to the result list.
        for (Resource listItem : listRoots) {
            RdfTree root = new RdfTree(modelNoRefData, nameResolver, list, listItem, null, false);
            buildTree(modelNoRefData, root, nameResolver, listRoots);
            list.addChild(root);
        }

        return list;
    }

    private RdfTree buildRdfList(Model model, NameResolver nameResolver, List<Resource> listItems) throws RdfTreeException {

        // Create the single root node
        RdfTree list = new RdfTree(model, nameResolver);

        // Add list items
        for (Resource listItem : listItems) {
            list.addListItem(listItem);
        }

        while (!list.isFullyConstructed()) {
            for (RdfTree childTree : list.getChildren()) {
                expandRdfTree(model, childTree);
            }
        }

        return list;
    }

    private RdfTree expandRdfTree(Model model, RdfTree current) {
        if (!current.isConstructed()) {

            // If this is a URI;
            if (current.getNode().isResource()) {

                // Find all statements in which this resource is the subject.
                List<Statement> statements = model.listStatements(new SimpleSelector(
                        current.getNode().asResource(),
                        null,
                        (RDFNode) null)).toList();

                // Find all statements in which this resource is the object.
                List<Statement> inverseStatements = model.listStatements(new SimpleSelector(
                        null,
                        null,
                        (RDFNode) current.getNode())).toList();

                inverseStatements.removeAll(statements);

                // If this URI is RDF `type', then mark it as so.
                List<Statement> types = model.listStatements(new SimpleSelector(
                        current.getNode().asResource(),
                        model.getProperty(RdfTree.RDF_TYPE),
                        (RDFNode) null)).toList();

                if (types.size() == 1) {
                    current.setType(types.get(0).getObject().asResource());
                }

                // Add statements as children of `current'
                handleStatements(current, statements);
                handleStatements(current, inverseStatements);
            }

            current.markAsConstructed();

        } else {
            if (current.getNode().isResource()) {
                for (RdfTree childTree : current.getChildren()) {
                    expandRdfTree(model, childTree);
                }
            }
        }

        return current;
    }

    private void handleStatements(RdfTree current, List<Statement> statements) {
        for (Statement statement : statements) {
            if (!statement.getPredicate().getNameSpace().equals(rdfResultOntologyPrefix)) {
                current.addChild(statement);
            }
        }
    }

    private List<Statement> getSomeStatements(Model model, SimpleSelector selector, String notFoundMessage) throws RdfTreeException {
        StmtIterator statements = model.listStatements(selector);

        if (!statements.hasNext()) {
            throw new RdfTreeException(notFoundMessage);
        }

        return statements.toList();
    }

    private Statement getNoneOrSingleStatement(Model model, SimpleSelector selector, String tooManyMessage) throws RdfTreeException {
        StmtIterator statements = model.listStatements(selector);

        if (!statements.hasNext()) {
            return null;
        }

        Statement only = statements.nextStatement();
        if (statements.hasNext()) throw new RdfTreeException(tooManyMessage);
        return only;
    }
}
