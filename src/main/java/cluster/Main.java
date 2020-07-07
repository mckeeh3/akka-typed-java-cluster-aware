package cluster;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;

class Main {
  static Behavior<Void> create() {
    return Behaviors.setup(context -> {
      bootstrap(context);

      return Behaviors.receive(Void.class)
          .onSignal(Terminated.class, signal -> Behaviors.stopped()).build();
    });
  }

  private static void bootstrap(final ActorContext<Void> context) {
    context.spawn(ClusterListenerActor.create(), ClusterListenerActor.class.getSimpleName());

    final ActorRef<HttpServer.PingStatistics> httpServerActorRef = context.spawn(HttpServerActor.create(), HttpServerActor.class.getSimpleName());

    context.spawn(ClusterAwareActor.create(httpServerActorRef), ClusterAwareActor.class.getSimpleName());
  }
}
