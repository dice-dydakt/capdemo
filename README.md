# CAP lab exercise

The goal of this lab is to teach the following concepts:
- [CAP theorem](https://en.wikipedia.org/wiki/CAP_theorem)
- Consistency, Availability, Partition Tolerance
- Eventual consistency, weak data consistency guarantees
- [CRDT data structures](https://en.wikipedia.org/wiki/Conflict-free_replicated_data_type)

## CAP introduction:
CAP theorem, also known as Brewer's conjecture, is a fundamental concept in
distributed computing that was formulated by a computer scientist Eric Brewer in 2000. 
It states that in a distributed system, you can achieve at most two out of three desirable
properties: Consistency, Availability, and Partition tolerance 
([formal proof](https://users.ece.cmu.edu/~adrian/731-sp04/readings/GL-cap.pdf)). 
_Consistency_ refers to all nodes in the system having the same data at the same time, _Availability_ 
means that every request to the system gets a response (not necessarily the most up-to-date), 
while _Partition tolerance_ denotes the system's capability to continue the operation despite network 
partitions or failures. The CAP theorem helps system designers make trade-offs in distributed systems 
to meet their specific requirements and constraints.

<img src="images/img.png" alt="img" style="width:400px;"/>

## Hazelcast:
Hazelcast is realtime data platform. It provides data structures that enable the construction
of distributed systems, [ensuring AP or CP guarantees](https://docs.hazelcast.com/hazelcast/5.3/architecture/architecture#apcp) from the CAP theory. 
(CA is not considered as hazelcast focuses on distributed systems, where partition tolerance is crucial)


GitHub: https://github.com/hazelcast/hazelcast

Documentation: https://docs.hazelcast.com/hazelcast/5.3/


## Exercises:

### CPSubsystem:
CPSubsystem is a part of Hazelcast that enables user to create a CP cluster with strongly consistent data structures. 
In CPSubsystem Hazelcast uses Raft algorithm to reach consensus between nodes in cluster. The subsystem 
is described and modified by several variables which change the behaviour of the cluster. The most important are:


- cp-member-count - describes the total number of nodes in cluster
- group-size - describes the number of nodes participating in reaching consensus over state of the cluster.
  It is an odd number, which is better than an event number due to quorum or majority calculations.

For a CP group of N members:

- the majority of members is calculated as `(N + 1) / 2`.
- the number of failing members that can be tolerated is calculated as `(N - 1) / 2`.

For example, in a CP group of five CP members, operations are committed when they are replicated
to at least three CP members. This CP group can tolerate the failure of two CP members and remain available.

Configuration used in exercise 1:

```
    <cp-subsystem>
        <cp-member-count>3</cp-member-count>
        <group-size>3</group-size>
        <session-time-to-live-seconds>300</session-time-to-live-seconds>
        <session-heartbeat-interval-seconds>5</session-heartbeat-interval-seconds>
        <missing-cp-member-auto-removal-seconds>14400</missing-cp-member-auto-removal-seconds>
        <fail-on-indeterminate-operation-state>false</fail-on-indeterminate-operation-state>
    </cp-subsystem>
```

More info about CPSubsytem: https://docs.hazelcast.com/hazelcast/5.3/cp-subsystem/cp-subsystem

### Scenario:
All exercises create a cluster with 3 nodes. The goal of the lab is to find out how the cluster behaves
when a network partitioning occurs. The partitioning is simulated with the ``hazelcast.test``  package
which provides a new way to create hazelcast instances with firewalling capabilities.
To isolate nodes from each other we use function `SplitBrainTestSupport.blockCommunicationBetween()`
which applies firewall between them.

### How to run:
1. Install maven
2. Build with `mvn compile`
3. Run `mvn exec:java@ex1`, `mvn exec:java@ex2` or `mvn exec:java@ex3` depending on the exercise

### Viewing logs

Hazelcast supports logging of operations  currently performed in the cluster. The programs
generate logs into `log/hazelcast.log` to make the command prompt more readable.
(configuration of logging is accessible in `resources/log4j2.properties` file) 

To view the logs in real time you can use linux command:

```
tail -f log/hazelcast.log
``` 






### Exercise 1:

Exercises 1 and 2 are focused on CP guarantees. All 3 nodes of the cluster are in a single group of a CPSubsystem.
In order to demonstrate the behavior of the cluster we will use variable of type: ``AtomicLong`` from the CPSubsytem.

https://docs.hazelcast.com/hazelcast/5.3/data-structures/iatomiclong

In this exercise we isolate one node from others as it is shown on the diagram below.

![img_2.png](images/img_2.png)


Steps:

- Start the example in Ex1 package
- use the command prompt with provided functions to perform the steps below
- get the value of the ``AtomicLong`` (it should return 0)
- increase value of ``AtomicLong`` on one node and check if other nodes see the change
- initiate a network partition  
- check if you can retrieve the variable from the isolated node
- check if you can retrieve the variable from other nodes
- try to increase the value of ``AtomicLong`` from both sides of the network partition (what happens?)
- heal the network partition 
- check what happened with the ``AtomicLong`` values on all nodes (healing may take some time)

#### Question:
Explain if and why you were/were not able to get/increase the value of ``AtomicLong`` in each step on particular nodes.


### Exercise 2:

This exercise is similar to Exercise 1, however, in this case we isolate all nodes which
causes lack of communication in the cluster.

![img_3.png](images/img_3.png)

Steps:

- Start the example in Ex2 package
- use the command prompt with provided functions to perform the steps below
- get the value of the ``AtomicLong`` on different nodes (it should return 0)
- increase value of ``AtomicLong`` on one node and check if other nodes see the change
- inititate a network partition
- check if you can retrieve the variable or increase its value from any isolated node
- heal the network partition (in this case healing partition can take some time due to 
problems with achieving nodes agreement)
- check what happened with ``AtomicLong`` values on all nodes afterwards

#### Question:
Explain if and why you were/were not able to get/increase the value of ``AtomicLong`` in each step on particular nodes.

### Exercise 3:

This exercise is focused on the AP guarantees. To demonstrate the behavior of the cluster we will use variable of type:
``PNCounter`` from the Hazelcast instance. ``PNCounter`` is a [CRDT data structure](https://en.wikipedia.org/wiki/Conflict-free_replicated_data_type) which provides eventual consistency. In other words, it sacrifices strong consistency in favour of availability.

https://docs.hazelcast.com/hazelcast/5.3/data-structures/pn-counter

We will use same partitioning as in exercise 1 (isolating only one node) 
but we will not create the CPSubsystem or node group.


Steps:

- Start the example in the Ex3 package
- use the command prompt with provided functions to perform the steps below
- get the value of the ``PNCounter`` (it should return 0)
- increase value of ``PNCounter`` on one node and check if other nodes see the change
- initiate a network partition
- check if you can retrieve the variable from the isolated node
- check if you can retrieve the variable from other nodes
- try to increase value of ``PNCounter`` from both sides of the network partition (what happens?)
- heal the network partition
- check what happened with the ``PNCounter`` values on all nodes afterwards

#### Question:
Explain if and why you were/were not able to get/increase the value of ``PNCounter`` in each step on particular nodes.

## Assignment

Submit a report that explains the following:
- (3p) Answer the questions found above in the description of Exercises 1,2,3.
- (2p) Which data structures -- AP or CP -- require the Raft algorith? Why is this algorithm needed?
- (2p) Read article [_Session Guarantees for Weakly Consistent Replicated Data_](https://www.cs.utexas.edu/users/lorenzo/corsi/cs380d/papers/SessionGuaranteesBayou.pdf) and explain what it means that the PN Counter in Hazelcast provides _Read-Your-Writes_ (RYW) and _Monotonic reads_ guarantees and why they are _session guarantees_.  

