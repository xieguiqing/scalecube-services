package io.scalecube.services.examples.services.factory;

import com.google.inject.AbstractModule;
import com.google.inject.Binding;
import com.google.inject.BindingAnnotation;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import io.scalecube.services.Microservices;
import io.scalecube.services.MicroservicesContext;
import io.scalecube.services.ServiceCall;
import io.scalecube.services.ServiceDefinition;
import io.scalecube.services.ServiceFactory;
import io.scalecube.services.ServiceInfo;
import io.scalecube.services.discovery.ScalecubeServiceDiscovery;
import io.scalecube.services.discovery.api.ServiceDiscovery;
import io.scalecube.services.examples.helloworld.service.GreetingServiceImpl;
import io.scalecube.services.examples.services.factory.service.BidiGreetingImpl;
import io.scalecube.services.examples.services.factory.service.api.BidiGreetingService;
import io.scalecube.services.examples.services.factory.service.api.GreetingsService;
import io.scalecube.services.inject.ScalecubeServiceFactory;
import io.scalecube.services.transport.rsocket.RSocketServiceTransport;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

public class GuiceServiceFactoryExample {

  /**
   * Main method.
   *
   * @param args - program arguments
   */
  public static void main(String[] args) {
    ServiceFactory serviceFactory2 =
        ScalecubeServiceFactory.fromInstances(new GreetingServiceImpl());

    Microservices service2Node =
        Microservices.builder()
            .serviceFactory(serviceFactory2)
            .discovery(ScalecubeServiceDiscovery::new)
            .transport(RSocketServiceTransport::new)
            .startAwait();

    ServiceFactory serviceFactory1 = new GuiceServiceFactory(new SampleModule());

    Microservices service1Node =
        Microservices.builder()
            .discovery(
                endpoint ->
                    new ScalecubeServiceDiscovery(endpoint)
                        .membership(cfg -> cfg.seedMembers(service2Node.discovery().address())))
            .serviceFactory(serviceFactory1)
            .transport(RSocketServiceTransport::new)
            .startAwait();

    service1Node
        .call()
        .api(BidiGreetingService.class)
        .greeting()
        .log("receive     |")
        .log()
        .log("complete    |")
        .block();

    Mono.whenDelayError(service1Node.shutdown(), service2Node.shutdown()).block();
  }

  @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
  @Retention(RetentionPolicy.RUNTIME)
  @BindingAnnotation
  @interface ScalecubeBean {

  }

  public static class SampleModule extends AbstractModule {

    @Override
    protected void configure() {
      try {
        bind(GreetingsService.class)
            .toProvider(
                new Provider<>() {

                  @Inject
                  private ServiceCall serviceCall;

                  @Override
                  public GreetingsService get() {
                    return serviceCall.api(GreetingsService.class);
                  }
                });
        Constructor<BidiGreetingImpl1> constructor =
            BidiGreetingImpl1.class.getConstructor(GreetingsService.class);
        bind(BidiGreetingService.class)
            .annotatedWith(ScalecubeBean.class)
            .toConstructor(constructor)
            .in(Scopes.SINGLETON);
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      }
    }
  }

  @ScalecubeBean
  public static class BidiGreetingImpl1 extends BidiGreetingImpl implements BidiGreetingService {

    @Inject
    public BidiGreetingImpl1(GreetingsService greetingsService) {
      super(greetingsService);
    }
  }

  public static class GuiceServiceFactory implements ServiceFactory {

    private final List<Module> modules;
    private Injector injector;

    private GuiceServiceFactory(Module... modules) {
      this.modules = Arrays.asList(modules);
    }

    @Override
    public Mono<? extends Collection<ServiceDefinition>> getServiceDefinitions(
        MicroservicesContext microservices) {
      return Mono.fromCallable(
          () -> {
            AbstractModule baseModule =
                new AbstractModule() {

                  @Override
                  protected void configure() {
                    bind(MicroservicesContext.class).toInstance(microservices);
                    bind(ServiceCall.class).toProvider(microservices::serviceCall);
                    bind(ServiceDiscovery.class).toProvider(microservices::serviceDiscovery);
                  }
                };
            List<Module> modules = new ArrayList<>();
            modules.add(baseModule);
            modules.addAll(this.modules);
            Injector injector = Guice.createInjector(modules);

            this.injector = injector;
            return injector.getAllBindings().keySet().stream()
                .filter(key -> key.getAnnotationType() == ScalecubeBean.class)
                .map(Key::getTypeLiteral)
                .map(TypeLiteral::getRawType)
                .map(ServiceDefinition::new)
                .collect(Collectors.toList());
          });
    }

    @Override
    public Mono<? extends Collection<ServiceInfo>> initializeServices(
        MicroservicesContext microservices) {
      return Mono.fromCallable(
          () ->
              this.injector.getAllBindings().values().stream()
                  .filter(binding -> binding.getKey().getAnnotationType() == ScalecubeBean.class)
                  .map(Binding::getProvider)
                  .map(Provider::get)
                  .map(bean -> ServiceInfo.fromServiceInstance(bean).build())
                  .collect(Collectors.toList()));
    }
  }
}