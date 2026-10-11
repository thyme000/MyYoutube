package cn.diaojiahong.myyoutube.transcode;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.rocketmq.client.consumer.*;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.springframework.cloud.loadbalancer.core.*;
import org.springframework.cloud.client.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="pipeline.live",matches="true")
class LiveInfrastructureTest {
    @Test void nacosDiscoveryFeedsSpringLoadBalancerAcrossTwoHttpInstances() throws Exception {
        String service="pipeline-live-"+UUID.randomUUID();
        var first=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var second=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        for(var server:List.of(first,second)) server.createContext("/health",exchange -> {
            byte[] bytes=Integer.toString(server.getAddress().getPort()).getBytes();
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        first.start(); second.start();
        var properties=new Properties(); properties.setProperty("serverAddr","127.0.0.1:18848");
        properties.setProperty("nacos.naming.cache.dir","/tmp/myyoutube-nacos-tests");
        NamingService naming=NacosFactory.createNamingService(properties);
        NamingService other=NacosFactory.createNamingService(properties);
        try {
            naming.registerInstance(service,"127.0.0.1",first.getAddress().getPort());
            other.registerInstance(service,"127.0.0.1",second.getAddress().getPort());
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            List<com.alibaba.nacos.api.naming.pojo.Instance> instances;
            do { instances=naming.selectInstances(service,true); if(instances.size()==2) break; Thread.sleep(200); } while(System.nanoTime()<deadline);
            assertEquals(2,instances.size());
            var discovered=instances.stream().<ServiceInstance>map(i -> new DefaultServiceInstance(i.getInstanceId(),service,i.getIp(),i.getPort(),false)).toList();
            ServiceInstanceListSupplier supplier=new ServiceInstanceListSupplier() {
                public String getServiceId(){return service;}
                public Flux<List<ServiceInstance>> get(){return Flux.just(discovered);}
            };
            var beans=new StaticListableBeanFactory(); beans.addBean("instances",supplier);
            var balancing=new RoundRobinLoadBalancer(beans.getBeanProvider(ServiceInstanceListSupplier.class),service);
            var hits=new HashMap<Integer,Integer>(); var client=HttpClient.newHttpClient();
            for(int i=0;i<10;i++) {
                var instance=balancing.choose().block().getServer();
                var response=client.send(HttpRequest.newBuilder(URI.create(instance.getUri()+"/health")).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode()); hits.merge(Integer.parseInt(response.body()),1,Integer::sum);
            }
            assertEquals(Set.of(5),new HashSet<>(hits.values())); assertEquals(2,hits.size());
            System.out.println("LIVE Nacos discovery + Spring load balancing: "+hits);
        } finally {
            naming.deregisterInstance(service,"127.0.0.1",first.getAddress().getPort());
            other.deregisterInstance(service,"127.0.0.1",second.getAddress().getPort());
            naming.shutDown(); other.shutDown(); first.stop(0); second.stop(0);
        }
    }
    @Test void rocketMqInfrastructureFailureReconsumesThreeTimesThenRetainsDeadLetter() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","");
        String topic="pipeline_live_"+suffix,group="pipeline_live_consumer_"+suffix;
        var producer=new DefaultMQProducer("pipeline_live_producer_"+suffix); producer.setNamesrvAddr(IsolatedDownloadSupport.NAMESERVER);
        var consumer=new DefaultMQPushConsumer(group); consumer.setNamesrvAddr(IsolatedDownloadSupport.NAMESERVER);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET); consumer.setMaxReconsumeTimes(3);
        consumer.setConsumeThreadMin(1); consumer.setConsumeThreadMax(1);
        consumer.subscribe(topic,"*"); var calls=new AtomicInteger();
        consumer.registerMessageListener((MessageListenerConcurrently)(messages,context)-> {
            calls.incrementAndGet(); context.setDelayLevelWhenNextConsume(1); return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        });
        var probe=new DefaultMQPullConsumer("pipeline_live_probe_"+suffix); probe.setNamesrvAddr(IsolatedDownloadSupport.NAMESERVER);
        try {
            producer.start(); producer.send(new Message(topic,"failure".getBytes())); consumer.start(); probe.start();
            boolean retained=false; long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
            while(System.nanoTime()<deadline) {
                try {
                    for(var queue:probe.fetchSubscribeMessageQueues("%DLQ%"+group))
                        retained|=probe.maxOffset(queue)>probe.minOffset(queue);
                } catch(org.apache.rocketmq.client.exception.MQClientException absent) { /* DLQ appears after the last retry. */ }
                if(retained) break; Thread.sleep(200);
            }
            assertTrue(retained,"Dead letter must remain in broker storage for manual intervention");
            assertEquals(4,calls.get());
            var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            var environment=new org.springframework.mock.env.MockEnvironment().withProperty("spring.application.name","live-"+suffix)
                    .withProperty("rocketmq.name-server",IsolatedDownloadSupport.NAMESERVER).withProperty("media.monitor.dlq-groups",group);
            var monitoring=new cn.diaojiahong.myyoutube.common.monitoring.DeadLetterMetrics(environment,registry);
            try { monitoring.sample(); assertEquals(1,registry.get("mq.dlq.retained").tag("group",group).gauge().value()); }
            finally { monitoring.close(); }
            System.out.println("LIVE RocketMQ: executions="+calls.get()+", retained DLQ=true, group="+group);
        } finally { consumer.shutdown(); probe.shutdown(); producer.shutdown(); }
    }
}
