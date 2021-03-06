package org.elasticsearch.monitor.parfait;

import com.custardsource.parfait.*;
import com.custardsource.parfait.dxm.IdentifierSourceSet;
import com.custardsource.parfait.dxm.PcpMmvWriter;
import com.custardsource.parfait.io.ByteCountingInputStream;
import com.custardsource.parfait.io.ByteCountingOutputStream;
import com.custardsource.parfait.jmx.JmxView;
import com.custardsource.parfait.pcp.*;
import com.custardsource.parfait.spring.SelfStartingMonitoringView;
import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.jmx.JmxService;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.Collections;
import java.util.regex.Pattern;


public class ParfaitService extends AbstractLifecycleComponent<Void> {
    private final MonitorableRegistry monitorableRegistry;
    private final SelfStartingMonitoringView selfStartingMonitoringView;


    private static final int ELASTICSEARCH_PCP_CLUSTER_IDENTIFIER  = 0xB01; /*NG BO1NG - funny... ok you had to be there*/

    public ParfaitService(Settings settings) {
        super(settings);
        monitorableRegistry = new MonitorableRegistry();
        // TODO metricsources etc
        // TODO use different mmv writer name based on cluster name
        Reader instanceData = new StringReader(""); // TODO empty for now, but could have different instance dimensions for different types of operations
        Reader metricData = new StringReader("elasticsearch.index.bulk  1"); // TODO, put this into a resource file

        IdentifierSourceSet fallbacks = IdentifierSourceSet.DEFAULT_SET;
        Boolean isData = settings.getAsBoolean("node.data", false);
        Boolean isClient = settings.getAsBoolean("node.client", false);
        boolean isServer = isData || !isClient;

        String nodeType = isServer ? "server" : "client";
        System.out.printf("isData=%s, isClient=%s, isServer=%s, nodeType=%s\n", isData, isClient, isServer, nodeType);

        IdentifierSourceSet identifierSourceSet = IdentifierSourceSet.DEFAULT_SET;//new FileParsingIdentifierSourceSet(instanceData, metricData, fallbacks);
        final PcpMmvWriter mmvWriter = new PcpMmvWriter("elasticsearch-" + nodeType + ".mmv", identifierSourceSet);
        mmvWriter.setClusterIdentifier(ELASTICSEARCH_PCP_CLUSTER_IDENTIFIER);


        // TODO obviously this is not configured nicely yet, but shows the pattern to re-register sub-trees as domains
        // RegexSequenceNameMapper.Replacement replacement = new RegexSequenceNameMapper.Replacement(Pattern.compile("elasticsearch.index.bulk.([^\\.]+).([^\\.+])"), "elasticsearch.index.bulk[$1/$2]");
        // sRegexSequenceNameMapper regexSequenceNameMapper = new RegexSequenceNameMapper(Collections.singletonList(replacement));

        final PcpMonitorBridge pcpMonitorBridge = new PcpMonitorBridge(mmvWriter, MetricNameMapper.PASSTHROUGH_MAPPER, new MetricDescriptionTextSource(), new EmptyTextSource());

        // TODO whoops, forgot that JmxView relies on the Spring @ManagedResource stuff to expose, so need to get passed in the JmxService
        final JmxView jmxView = new JmxView();
        final CompositeMonitoringView compositeMonitoringView = new CompositeMonitoringView(pcpMonitorBridge, jmxView);
        selfStartingMonitoringView = new SelfStartingMonitoringView(compositeMonitoringView, 2000);
    }


    public Monitorable<?> createMoniteredLongValue(String name, String description, Long initialValue) {
        return register(new MonitoredLongValue(name, description, initialValue));
    }

    public MonitoredCounter createMoniteredCounter(String name, String description) {
        return register(new MonitoredCounter(name, description));
    }


    private <T extends Monitorable> T register(T monitorable) {
        // TODO need to check if the monitorable exits and resue it, this is particularly noticeable if Indexes are deleted and recreated
        // need to merge in Cowan's latest changes to see this though
        monitorableRegistry.register(monitorable);
        return monitorable;
    }

    public ByteCountingOutputStream wrapAsCountingOutputStream(OutputStream out, Counter existingCounter) {
        return new ByteCountingOutputStream(out, existingCounter);
    }

    public ByteCountingInputStream wrapAsCountingInputStream(InputStream is, Counter existingCounter) {
        return new ByteCountingInputStream(is, existingCounter);
    }

    @Override protected void doStart() {
        selfStartingMonitoringView.start();
    }

    @Override protected void doStop() {
        selfStartingMonitoringView.stop();
    }

    @Override protected void doClose() {
    }

    public MonitoredCounterBuilder forShard(ShardId shardId) {
        return new MonitoredCounterBuilder(shardId);
    }
    public final class MonitoredCounterBuilder {


        private final ShardId shardId;

        public MonitoredCounterBuilder(ShardId shardId) {
            this.shardId = shardId;
        }


        public MonitoredCounter count(String op) {
            return createMoniteredCounter(String.format("elasticsearch.index[%s/%s].%s.count", shardId.getIndex(), shardId.id(), op), String.format("# %s Operations performed by the engine for a given shard", StringUtils.capitalize(op)));
        }
    }

}
