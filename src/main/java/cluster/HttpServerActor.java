package cluster;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import org.slf4j.Logger;

class HttpServerActor {
  private final ActorContext<HttpServer.PingStatistics> context;
  private final HttpServer httpServer;

  HttpServerActor(ActorContext<HttpServer.PingStatistics> context) {
    this.context = context;

    httpServer = HttpServer.start(context.getSystem());
  }

  static Behavior<HttpServer.PingStatistics> create() {
    return Behaviors.setup(context -> new HttpServerActor(context).behavior());
  }

  private Behavior<HttpServer.PingStatistics> behavior() {
    return Behaviors.receive(HttpServer.PingStatistics.class)
        .onMessage(HttpServer.PingStatistics.class, this::onStatistics)
        .build();
  }

  private Behavior<HttpServer.PingStatistics> onStatistics(HttpServer.PingStatistics pingStatistics) {
    log().info("Statistics {} {}", pingStatistics.totalPings, pingStatistics.nodePings);
    httpServer.load(pingStatistics);
    return Behaviors.same();
  }

  private Logger log() {
    return context.getLog();
  }
}
