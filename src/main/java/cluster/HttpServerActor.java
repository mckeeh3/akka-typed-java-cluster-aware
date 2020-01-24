package cluster;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import org.slf4j.Logger;

class HttpServerActor {
    private final ActorContext<ClusterAwareActor.Statistics> context;
    private final HttpServer httpServer;

    HttpServerActor(ActorContext<ClusterAwareActor.Statistics> context) {
        this.context = context;

        httpServer = HttpServer.start(context.getSystem());
    }

    static Behavior<ClusterAwareActor.Statistics> create() {
        return Behaviors.setup(context -> new HttpServerActor(context).behavior());
    }

    private Behavior<ClusterAwareActor.Statistics> behavior() {
        return Behaviors.receive(ClusterAwareActor.Statistics.class)
                .onMessage(ClusterAwareActor.Statistics.class, this::onStatistics)
                .build();
    }

    private Behavior<ClusterAwareActor.Statistics> onStatistics(ClusterAwareActor.Statistics statistics) {
        log().info("Statistics {} {}", statistics.totalPongs, statistics.nodePongs);
        return Behaviors.same();
    }

    private Logger log() {
        return context.getLog();
    }
}
