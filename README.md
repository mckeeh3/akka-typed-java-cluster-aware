## Akka Java Cluster Aware Example
> **WARNING**: This README is undergoing extensive modifications.

> **WARNING**: The current contents are not relevant to this project.  

### Introduction

This is a Java, Maven, Akka project that demonstrates how to setup a basic
[Akka Cluster](https://doc.akka.io/docs/akka/current/index-cluster.html) with a focus on cluster aware actors.

This project is one in a series of projects that starts with a simple Akka Cluster project and progressively builds up to examples of event sourcing and command query responsibility segregation.

The project series is composed of the following GitHub repos:
* [akka-java-cluster](https://github.com/mckeeh3/akka-java-cluster)
* [akka-java-cluster-aware](https://github.com/mckeeh3/akka-java-cluster-aware) (this project)
* [akka-java-cluster-singleton](https://github.com/mckeeh3/akka-java-cluster-singleton)
* [akka-java-cluster-sharding](https://github.com/mckeeh3/akka-java-cluster-sharding)
* [akka-java-cluster-persistence](https://github.com/mckeeh3/akka-java-cluster-persistence)
* [akka-java-cluster-persistence-query](https://github.com/mckeeh3/akka-java-cluster-persistence-query)

Each project can be cloned, built, and runs independently of the other projects.

### About Akka Clustering Awareness

Before we get into the details of how this project is set up as a cluster-aware example, let's first use a simple example scenario. In this example, we'll use a basic chat room app. As people enter the chat room, they can see a list of who else is in the chat room. Every person in the chat room is given a simple set of instructions, send a message with the word "ping" every minute to all of the other people currently in the chat room. When you receive a "ping" message respond to the sender with the word "pong."

In this example, each person in the chat room is essentially a simple actor that is following a simple set of instructions. Also, each person is aware of who else is in the chat room and that all of the other participants are following the same set of instructions.

This example scenario is similar to the fundamental approach used by aware cluster actors. Cluster-aware actor classes are implemented with the expectation that an instance of the actor is running on each node in a cluster. The cluster-aware actors access a list of each of the nodes in the cluster as a way to send messages to its clones running on the other nodes.

Message routing is the most common cluster-aware usage pattern. Messages sent to cluster-aware router actors are forwarded to other actors that are distributed across the cluster. For example, router actors work in conjunction with worker actors. Messages are sent that contains a worker identifier. These messages may be sent to any router actor. When a router actor receives each message, it looks at the id provided in the message to determine if the message belongs to one of the worker actors running on the same node as the router or if the worker for that identifier is located on another node. If the message is for a local actor, the router forwards it to the local worker. For messages that belong to remote workers, the router forwards the message to the remote router. The remote router goes through the same process. It looks at the message id to determine how the message should be routed.

This project includes a simple cluster-aware actor that periodically sends ping messages and responds to ping messages with pong messages sent back to the pinger. The key thing to understand that this messaging is happening between Akka cluster nodes, each node is running as a separate JVM, and the messages are sent over the network.

~~~java
package cluster;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorSelection;
import akka.actor.Cancellable;
import akka.actor.Props;
import akka.cluster.Cluster;
import akka.cluster.Member;
import akka.cluster.MemberStatus;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

class ClusterAwareActor extends AbstractLoggingActor {
    private final Cluster cluster = Cluster.get(context().system());
    private final FiniteDuration tickInterval = Duration.create(10, TimeUnit.SECONDS);
    private Cancellable ticker;

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .matchEquals("tick", s -> tick())
                .match(Message.Ping.class, this::ping)
                .match(Message.Pong.class, this::pong)
                .build();
    }

    private void tick() {
        Member me = cluster.selfMember();
        log().debug("Tick {}", me);

        cluster.state().getMembers().forEach(member -> {
            if (!me.equals(member) && member.status().equals(MemberStatus.up())) {
                tick(member);
            }
        });
    }

    private void tick(Member member) {
        String path = member.address().toString() + self().path().toStringWithoutAddress();
        ActorSelection actorSelection = context().actorSelection(path);
        Message.Ping ping = new Message.Ping();
        log().debug("{} -> {}", ping, actorSelection);
        actorSelection.tell(ping, self());
    }

    private void ping(Message.Ping ping) {
        log().debug("{} <- {}", ping, sender());
        sender().tell(Message.Pong.from(ping), self());
    }

    private void pong(Message.Pong pong) {
        log().debug("{} <- {}", pong, sender());
    }

    @Override
    public void preStart() {
        log().debug("Start");
        ticker = context().system().scheduler()
                .schedule(Duration.Zero(),
                        tickInterval,
                        self(),
                        "tick",
                        context().system().dispatcher(),
                        null);
    }

    @Override
    public void postStop() {
        ticker.cancel();
        log().debug("Stop");
    }

    static Props props() {
        return Props.create(ClusterAwareActor.class);
    }

    interface Message {
        class Ping implements Serializable {
            final long time;

            Ping() {
                time = System.nanoTime();
            }

            @Override
            public String toString() {
                return String.format("%s[%dus]", getClass().getSimpleName(), time);
            }
        }

        class Pong implements Serializable {
            final long pingTime;

            private Pong(long pingTime) {
                this.pingTime = pingTime;
            }

            static Pong from(Ping ping) {
                return new Pong(ping.time);
            }

            @Override
            public String toString() {
                final double elapsed = (System.nanoTime() - pingTime) / 1000000000.0;
                return String.format("%s[elapsed %.9fs, %dus]", getClass().getSimpleName(), elapsed, pingTime);
            }
        }
    }
}
~~~

The full class file of this example cluster aware actor is shown above. First, let's take a look at the method that handles incoming messages.

Note that there are three different message types, a tick message, and the ping and pong messages. We already have an idea what is going on with ping and pong, but what about the tick message?

The tick message is based on another common actor messaging pattern where an actor uses an Akka messaging scheduling feature.

~~~java
@Override
public void preStart() {
    log().debug("Start");
    ticker = context().system().scheduler()
            .schedule(Duration.Zero(),
                    tickInterval,
                    self(),
                    "tick",
                    context().system().dispatcher(),
                    null);
}
~~~

The `preStart` method is invoked when an actor instance is started. This method is often used to do some initial set up steps before the actor is ready to receive incoming messages. In this example, the actor schedules to send itself a "tick" message every interval cycle.

When a "tick" message is received this trigger the actor to send ping messages to its counterpart cluster-aware actors running on the other nodes in the cluster.

Let's walk through the code.

~~~java
@Override
public Receive createReceive() {
    return receiveBuilder()
            .matchEquals("tick", s -> tick())
            .match(Message.Ping.class, this::ping)
            .match(Message.Pong.class, this::pong)
            .build();
}
~~~
On every scheduled interval cycle a "tick" message is sent to the actor instance. The `createReceive` method defines how incoming messages are handled. In the case of a "tick" message, this triggers a call to the `tick()` method.
~~~java
private void tick() {
    Member me = cluster.selfMember();
    log().debug("Tick {}", me);

    cluster.state().getMembers().forEach(member -> {
        if (!me.equals(member) && member.status().equals(MemberStatus.up())) {
            tick(member);
        }
    });
}
~~~
The `tick()` method uses the `cluster` object to loop through all of the other members in the cluster. Note the if statement that filters out the current node, here called a member, and filters out nodes that are not in the "up" status. The `tick(member)` method is invoked for each of the up nodes.
~~~java
private void tick(Member member) {
    String path = member.address().toString() + self().path().toStringWithoutAddress();
    ActorSelection actorSelection = context().actorSelection(path);
    Message.Ping ping = new Message.Ping();
    log().debug("{} -> {}", ping, actorSelection);
    actorSelection.tell(ping, self());
}
~~~
The next step performed builds what is called an
[actor selection](https://doc.akka.io/docs/akka/current/general/addressing.html#how-are-actor-references-obtained-),
which is an Akka version of an actor URL. The final BIG step is completed in the last line of the method - `actorSelection.tell(ping, self());`. This single line of code tells the Akka actor system to send the ping object contents to the location specified in the actor selection. The resulting action is that the message is serialized, sent over the network to the destination node, deserialized, and then sent to the destination actor.

In this case the actor that receives ping messages is an instance of this actor's class. Going back to the `createReceive` method we can see how incoming ping messages are handled.

~~~java
private void ping(Message.Ping ping) {
    log().debug("{} <- {}", ping, sender());
    sender().tell(Message.Pong.from(ping), self());
}
~~~

Incoming ping messages are handled by the `ping(...)` method. When a ping message is received this method logs the incoming message object along with the sender location, which is an
[actor reference](https://doc.akka.io/docs/akka/current/general/addressing.html#what-is-an-actor-reference-),
similar to an actor selection discussed above. More importantly, a pong message is sent back to the sender.

This actor aware implementation functions much like the chat app example we covered at the start of this section. However, this example is just the starting point, as previously discussed, there are much more interesting cluster-aware patterns, such as message distribution patterns.

### Installation

~~~~bash
git clone https://github.com/mckeeh3/akka-java-cluster-aware.git
cd akka-java-cluster-aware
mvn clean package
~~~~

The Maven command builds the project and creates a self contained runnable JAR.

### Run a cluster (Mac, Linux)

The project contains a set of scripts that can be used to start and stop individual cluster nodes or start and stop a cluster of nodes.

The main script `./akka` is provided to run a cluster of nodes or start and stop individual nodes.
Use `./akka node start [1-9] | stop` to start and stop individual nodes and `./akka cluster start [1-9] | stop` to start and stop a cluster of nodes.
The `cluster` and `node` start options will start Akka nodes on ports 2551 through 2559.
Both `stdin` and `stderr` output is sent to a file in the `/tmp` directory using the file naming convention `/tmp/<project-dir-name>-N.log`.

Start node 1 on port 2551 and node 2 on port 2552.
~~~bash
./akka node start 1
./akka node start 2
~~~

Stop node 3 on port 2553.
~~~bash
./akka node stop 3
~~~

Start a cluster of four nodes on ports 2551, 2552, 2553, and 2554.
~~~bash
./akka cluster start 4
~~~

Stop all currently running cluster nodes.
~~~bash
./akka cluster stop
~~~

You can use the `./akka cluster start [1-9]` script to start multiple nodes and then use `./akka node start [1-9]` and `./akka node stop [1-9]`
to start and stop individual nodes.

Use the `./akka node tail [1-9]` command to `tail -f` a log file for nodes 1 through 9.

The `./akka cluster status` command displays the status of a currently running cluster in JSON format using the
[Akka Management](https://developer.lightbend.com/docs/akka-management/current/index.html)
extension
[Cluster Http Management](https://developer.lightbend.com/docs/akka-management/current/cluster-http-management.html).

### Run a cluster (Windows, command line)

The following Maven command runs a signle JVM with 3 Akka actor systems on ports 2551, 2552, and a radmonly selected port.
~~~~bash
mvn exec:java
~~~~
Use CTRL-C to stop.

To run on specific ports use the following `-D` option for passing in command line arguements.
~~~~bash
mvn exec:java -Dexec.args="2551"
~~~~
The default no arguments is equilevalant to the following.
~~~~bash
mvn exec:java -Dexec.args="2551 2552 0"
~~~~
A common way to run tests is to start single JVMs in multiple command windows. This simulates running a multi-node Akka cluster.
For example, run the following 4 commands in 4 command windows.
~~~~bash
mvn exec:java -Dexec.args="2551" > /tmp/$(basename $PWD)-1.log
~~~~
~~~~bash
mvn exec:java -Dexec.args="2552" > /tmp/$(basename $PWD)-2.log
~~~~
~~~~bash
mvn exec:java -Dexec.args="0" > /tmp/$(basename $PWD)-3.log
~~~~
~~~~bash
mvn exec:java -Dexec.args="0" > /tmp/$(basename $PWD)-4.log
~~~~
This runs a 4 node Akka cluster starting 2 nodes on ports 2551 and 2552, which are the cluster seed nodes as configured and the `application.conf` file.
And 2 nodes on randomly selected port numbers.
The optional redirect `> /tmp/$(basename $PWD)-4.log` is an example for pushing the log output to filenames based on the project direcctory name.

For convenience, in a Linux command shell define the following aliases.

~~~~bash
alias p1='cd ~/akka-java/akka-java-cluster'
alias p2='cd ~/akka-java/akka-java-cluster-aware'
alias p3='cd ~/akka-java/akka-java-cluster-singleton'
alias p4='cd ~/akka-java/akka-java-cluster-sharding'
alias p5='cd ~/akka-java/akka-java-cluster-persistence'
alias p6='cd ~/akka-java/akka-java-cluster-persistence-query'

alias m1='clear ; mvn exec:java -Dexec.args="2551" > /tmp/$(basename $PWD)-1.log'
alias m2='clear ; mvn exec:java -Dexec.args="2552" > /tmp/$(basename $PWD)-2.log'
alias m3='clear ; mvn exec:java -Dexec.args="0" > /tmp/$(basename $PWD)-3.log'
alias m4='clear ; mvn exec:java -Dexec.args="0" > /tmp/$(basename $PWD)-4.log'
~~~~

The p1-6 alias commands are shortcuts for cd'ing into one of the six project directories.
The m1-4 alias commands start and Akka node with the appropriate port. Stdout is also redirected to the /tmp directory.
