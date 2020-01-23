package cluster;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.TimerScheduler;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import com.fasterxml.jackson.annotation.JsonCreator;
import org.slf4j.Logger;
import scala.Option;

import java.io.Serializable;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

public class ClusterAwareActor {
    private final ActorContext<Message> context;
    private final Statistics statistics = new Statistics();
    private Set<ActorRef<Message>> serviceInstances;
    private Duration tickInterval = Duration.ofSeconds(10);

    static final ServiceKey<Message> serviceKey = ServiceKey.create(Message.class, ClusterAwareActor.class.getSimpleName());

    private ClusterAwareActor(ActorContext<Message> context) {
        this.context = context;
    }

    static Behavior<Message> create() {
        return Behaviors.setup(context ->
                Behaviors.withTimers(timers ->
                        new ClusterAwareActor(context).behavior(context, timers)));
    }

    private Behavior<Message> behavior(ActorContext<Message> context, TimerScheduler<Message> timers) {
        final ActorRef<Receptionist.Listing> listingActorRef = context.messageAdapter(Receptionist.Listing.class, Listeners::new);

        context.getSystem().receptionist()
                .tell(Receptionist.register(serviceKey, context.getSelf()));
        context.getSystem().receptionist()
                .tell(Receptionist.subscribe(serviceKey, listingActorRef));
        timers.startTimerAtFixedRate(Tick.Instance, tickInterval);

        return Behaviors.receive(Message.class)
                .onMessage(Listeners.class, this::onListeners)
                .onMessage(Tick.class, notUsed -> onTick())
                .onMessage(Ping.class, this::onPing)
                .onMessage(Pong.class, this::onPong)
                .build();
    }

    private Behavior<Message> onListeners(Listeners listeners) {
        serviceInstances = listeners.listing.getServiceInstances(serviceKey);
        statistics.clearOfflineNodeCounters(serviceInstances);

        log().info("Cluster aware actors subscribers changed, count {}", serviceInstances.size());
        serviceInstances
                .forEach(new Consumer<ActorRef<Message>>() {
                    int i = 0;

                    @Override
                    public void accept(ActorRef<Message> messageActorRef) {
                        log().info("{} {}{}", ++i, self(messageActorRef), messageActorRef);
                    }

                    private String self(ActorRef<Message> clusterAwareActorRef) {
                        return clusterAwareActorRef.equals(context.getSelf()) ? "(SELF) " : "";
                    }
                });

        return Behaviors.same();
    }

    private Behavior<Message> onTick() {
        int size = serviceInstances.size() - 1;
        log().info("Tick, ping {}", Math.max(size, 0));
        serviceInstances.stream()
                .filter(clusterAwareActorRef -> !clusterAwareActorRef.equals(context.getSelf()))
                .forEach(clusterAwareActorRef -> clusterAwareActorRef.tell(new Ping(context.getSelf(), System.currentTimeMillis())));
        return Behaviors.same();
    }

    private Behavior<Message> onPing(Ping ping) {
        log().info("<=={}", ping);
        ping.replyTo.tell(new Pong(context.getSelf(), ping.start));
        return Behaviors.same();
    }

    private Behavior<Message> onPong(Pong pong) {
        log().info("<--{}", pong);
        statistics.pong(pong.replyFrom);
        return Behaviors.same();
    }

    private Logger log() {
        return context.getLog();
    }

    public interface Message {
    }

    private static class Listeners implements Message {
        final Receptionist.Listing listing;

        private Listeners(Receptionist.Listing listing) {
            this.listing = listing;
        }
    }

    public static class Ping implements Message, Serializable {
        public final ActorRef<Message> replyTo;
        public final long start;

        @JsonCreator
        public Ping(ActorRef<Message> replyTo, long start) {
            this.replyTo = replyTo;
            this.start = start;
        }

        @Override
        public String toString() {
            return String.format("%s[%s]", getClass().getSimpleName(), replyTo.path());
        }
    }

    public static class Pong implements Message, Serializable {
        public final ActorRef<Message> replyFrom;
        public final long pingStart;

        @JsonCreator
        public Pong(ActorRef<Message> replyFrom, long pingStart) {
            this.replyFrom = replyFrom;
            this.pingStart = pingStart;
        }

        @Override
        public String toString() {
            return String.format("%s[%s, %dms]", getClass().getSimpleName(), replyFrom.path(), System.currentTimeMillis() - pingStart);
        }
    }

    enum Tick implements Message {
        Instance
    }

    static class Statistics {
        int totalPongs = 0;
        Map<Integer, Integer> nodePongs = new HashMap<>();

        void pong(ActorRef<Message> actorRef) {
            ++totalPongs;

            int port = actorRefPort(actorRef);
            if (port >= 2551 && port <= 2559) {
                nodePongs.put(port, 1 + nodePongs.getOrDefault(port, 0));
            }
        }

        void clearOfflineNodeCounters(Set<ActorRef<Message>> serviceInstances) {
            List<Integer> ports = new ArrayList<>();
            for (int p = 2551; p <= 2559; p++) {
                ports.add(p);
            }
            serviceInstances.forEach(actorRef -> {
                int port = actorRefPort(actorRef);
                if (ports.contains(port)) {
                    ports.remove((Integer) port);
                }
            });
            ports.forEach(port -> nodePongs.replace(port, 0));
        }

        private static int actorRefPort(ActorRef<Message> actorRef) {
            Option<Object> port = actorRef.path().address().port();
            return port.isDefined()
                    ? Integer.parseInt(port.get().toString())
                    : -1;
        }
    }
}
