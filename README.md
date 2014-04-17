# JSON-LD Tree

This library will allow the generation of stable, predictable JSON-LD based on RDF using the RDF Result ontology.

## This branch

In an effort to better understand the way that trees are built, this branch is an experimental re-implementation of the core tree-building bit intended to provide an alternative way of looking at the algorithm. It passes all current tests and a couple more have been added, but there were some constructs that seemed unnecessary, so that code has been omitted from the re-implemented sections.

This, therefore, comes with a disclaimer: Use at your own risk! There may be some critical bits missing.

# Getting started

To generate the JSON String, do the following:

```java
//Create JSON-LD
new RdfTreeGenerator().generateRdfTree(jenaModel).asJson()

//Create HTML structured in a similar way to JSON-LD
new RdfTreeGenerator().generateRdfTree(jenaModel).asHtml()
```

The RDF model must contain RDF Result ontology statements to indicate how the graph should be interpretted. This can come in three different forms.

Lists to be ordered by a predicate value:

```text
@prefix result: <http://purl.org/ontology/rdf-result/> .

result:this result:listItem <urn:a> .
result:this result:listItem <urn:b> .
result:this result:listItem <urn:c> .
result:this result:orderByPredicate <urn:p> .

<urn:a> <urn:p> "a" . 
<urn:b> <urn:p> "b" . 
<urn:c> <urn:p> "c" .
```

Linked Lists:

```text
@prefix result: <http://purl.org/ontology/rdf-result/> .

result:this result:next <urn:a> .
<urn:a> result:next <urn:b> .
<urn:b> result:next <urn:c> .

<urn:a> <urn:p> "a" . 
<urn:b> <urn:p> "b" . 
<urn:c> <urn:p> "c" .
```

Single items:

```text
@prefix result: <http://purl.org/ontology/rdf-result/> . s

result:this result:item <urn:a> .

<urn:a> <urn:p> "a" . 
```
