package it.unict;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import it.unict.telemetry.TelemetryService;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class TopologyReconciler implements Reconciler<Topology> {

  private static final Logger log = LoggerFactory.getLogger(TopologyReconciler.class);

  private final KubernetesClient client;

  @RestClient
  TelemetryService telemetryService;

  protected final Integer minCost = 1;

  protected final Integer maxCost = 100;

  public TopologyReconciler(KubernetesClient client) {
    this.client = client;
  }

  @Override
  public UpdateControl<Topology> reconcile(Topology resource, Context context) {
    resource.getSpec().getNodes().forEach(nodeName -> {
      Node node = client.nodes().withName(nodeName).get();

      Map<String,Float> latencyValues = telemetryService.getAvgNodeLatencies(nodeName).await().indefinitely();
      if (!latencyValues.isEmpty()) {
        Integer highest = Collections.max(latencyValues.values()).intValue();
        Integer lowest = Collections.min(latencyValues.values()).intValue();
        Integer oldRange = highest - lowest;
        Integer newRange = maxCost - minCost;

        latencyValues.entrySet().forEach(entry -> {
          node.getMetadata().getLabels().put(
                  "network.cost." + entry.getKey(),
                  String.valueOf((oldRange == 0) ? minCost : ((entry.getValue().intValue() - lowest) * newRange / oldRange) + minCost)
          );
        });
      }

      client.nodes().withName(nodeName).patch(node);
    });

    return UpdateControl.<Topology>noUpdate().rescheduleAfter(resource.getSpec().getRescheduleDelay(), TimeUnit.SECONDS);
  }
}

