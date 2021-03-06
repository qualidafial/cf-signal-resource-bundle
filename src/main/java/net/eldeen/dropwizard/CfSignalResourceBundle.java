package net.eldeen.dropwizard;

import static com.amazonaws.services.cloudformation.model.ResourceStatus.CREATE_IN_PROGRESS;
import static com.amazonaws.services.cloudformation.model.ResourceStatus.UPDATE_IN_PROGRESS;
import static com.google.common.base.Preconditions.checkNotNull;

import javax.inject.Inject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient;
import com.amazonaws.services.cloudformation.model.DescribeStackResourceRequest;
import com.amazonaws.services.cloudformation.model.DescribeStackResourceResult;
import com.amazonaws.services.cloudformation.model.ResourceSignalStatus;
import com.amazonaws.services.cloudformation.model.ResourceStatus;
import com.amazonaws.services.cloudformation.model.SignalResourceRequest;
import com.amazonaws.util.EC2MetadataUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signal the AutoScalingGroup via CloudFormation Signal when running on an AWS EC2 instance.
 */
public class CfSignalResourceBundle<T extends Configuration> implements ConfiguredBundle<T> {

  private static final Logger LOGGER = LoggerFactory.getLogger(CfSignalResourceBundle.class);

  private final Function<CfSignalResourceConfig, AmazonCloudFormation> cloudFormationSupplier;
  private final AtomicReference<AmazonCloudFormation> internalCloudFormation = new AtomicReference<>(null);

  public CfSignalResourceBundle() {
    cloudFormationSupplier = (cfSignalResourceConfig) -> {
      AmazonCloudFormation amazonCloudFormation = internalCloudFormation.get();

      if (amazonCloudFormation != null) {
        return amazonCloudFormation;
      }

      return internalCloudFormation.updateAndGet((unused) -> {

        AmazonCloudFormationClient amazonCloudFormationClient = new AmazonCloudFormationClient();

        String awsRegion = cfSignalResourceConfig.getAwsRegion();
        Region region;
        if (Strings.isNullOrEmpty(awsRegion)) {
          region = Regions.getCurrentRegion();
        }
        else {
          region = Region.getRegion(Regions.fromName(awsRegion));
        }
        amazonCloudFormationClient.setRegion(region);

        return amazonCloudFormationClient;
      });
    };
  }

  @Inject
  public CfSignalResourceBundle(AmazonCloudFormation amazonCloudFormation) {
    checkNotNull(amazonCloudFormation);
    cloudFormationSupplier = (config) -> amazonCloudFormation;
  }

  @Override
  public void initialize(Bootstrap<?> bootstrap) {
  }

  /**
   * Override this method to specify a {@link CfSignalResourceConfig}. Otherwise the config will be fetched from
   * {@linkplain T} when {@link #run(Configuration, Environment)} runs.
   * @return an {@link Optional} containing the config if it exists, by default {@link Optional#empty()}
   */
  protected Optional<CfSignalResourceConfig> getConfiguration() {
    return Optional.empty();
  }

  @Override
  public void run(T config, Environment environment) {

    final CfSignalResourceConfig cfSignalResourceConfig =
      getConfiguration().orElseGet(() -> getCfResourceBundleConfig(config));

    if (cfSignalResourceConfig.isSkip()) {
      LOGGER.info("Skipping signalling because CfSignalResourceConfig configuration value 'skip == true'");
      return;
    }

    final Optional<String> instanceId = getInstanceId(cfSignalResourceConfig);
    if (!instanceId.isPresent()) {
      LOGGER.warn("Unable to fetch EC2 Instance ID, assuming not running on AWS and thus not signalling");
      return;
    }

    environment.lifecycle()
               .addLifeCycleListener(
                 new CfSignalResourceLifcycleListener(cfSignalResourceConfig,
                                                      instanceId.get()));
  }

  private void sendSignal(CfSignalResourceConfig config, final String instanceId, boolean success) {
    try {
      AmazonCloudFormation client = cloudFormationSupplier.apply(config);

      DescribeStackResourceResult asgResource = client.describeStackResource(
          new DescribeStackResourceRequest()
              .withStackName(config.getStackName())
              .withLogicalResourceId(config.getAsgResourceName()));
      ResourceStatus status = ResourceStatus.fromValue(asgResource.getStackResourceDetail().getResourceStatus());

      if (status == CREATE_IN_PROGRESS || status == UPDATE_IN_PROGRESS) {
        SignalResourceRequest request = new SignalResourceRequest();
        request.setUniqueId(instanceId);
        request.setLogicalResourceId(config.getAsgResourceName());
        request.setStackName(config.getStackName());
        request.setStatus(success? ResourceSignalStatus.SUCCESS : ResourceSignalStatus.FAILURE);
        client.signalResource(request);
      }
      else {
        LOGGER.debug("No CloudFormation update in progress on ASG '" + config.getAsgResourceName()
                         + "'. Assuming an auto-scaling event is in progress, and thus not signalling.");
      }
    }
    catch (Exception e) {
      LOGGER.error("There was a problem signaling ASG `" + config.getAsgResourceName()
                   + " in CloudFormation Stack '" + config.getStackName() + "'", e);
    }
    finally {
      AmazonCloudFormation internalClient = internalCloudFormation.get();
      if (internalClient != null) {
        try {
          internalClient.shutdown();
        }
        catch (Exception e) {
          //an internal client shouldn't affect anyone else
          LOGGER.debug("problem closing the internal AmazonCloudFormation client", e);
        }
      }
    }
  }

  private CfSignalResourceConfig getCfResourceBundleConfig(final T config) {
    for (Method method : config.getClass().getMethods()) {
      if (CfSignalResourceConfig.class.equals(method.getReturnType())
        && method.getParameterCount() == 0) {
        try {
          final Object cfResourceBundleConfig = method.invoke(config);
          if (Objects.isNull(cfResourceBundleConfig)) {
            throw new IllegalStateException("when called, the method exposing 'CfSignalResourceConfig' in config returned a null value");
          }
          return (CfSignalResourceConfig) cfResourceBundleConfig;
        }
        catch (IllegalAccessException e) {
          throw new RuntimeException("method exposing 'CfSignalResourceConfig' must be accessible", e);
        }
        catch (InvocationTargetException e) {
          Throwables.propagate(e);
        }
      }
    }
    throw new IllegalStateException("config 'CfSignalResourceConfig' must either be provided by overriding 'getConfiguration()' or via"
                                      + " the Application Configuration '" + config.getClass() + "'");
  }

  private Optional<String> getInstanceId(CfSignalResourceConfig cfSignalResourceConfig) {
    return Optional.ofNullable(
      Strings.isNullOrEmpty(cfSignalResourceConfig.getEc2InstanceId()) ?
      EC2MetadataUtils.getInstanceId() : cfSignalResourceConfig.getEc2InstanceId());
  }

  @VisibleForTesting
  /*package-private*/ AmazonCloudFormation getInternalCloudFormation() {
    return internalCloudFormation.get();
  }

  @VisibleForTesting
  /*package-private*/ AmazonCloudFormation getCloudFormation(final CfSignalResourceConfig config) {
    return cloudFormationSupplier.apply(config);
  }

  @VisibleForTesting
  class CfSignalResourceLifcycleListener implements LifeCycle.Listener {

    private final CfSignalResourceConfig cfSignalResourceConfig;
    private final String instanceId;

    CfSignalResourceLifcycleListener(final CfSignalResourceConfig cfSignalResourceConfig, final String instanceId) {
      this.cfSignalResourceConfig = cfSignalResourceConfig;
      this.instanceId = instanceId;
    }

    @Override
    public void lifeCycleStarting(final LifeCycle event) {
      //dont care
    }

    @Override
    public void lifeCycleFailure(final LifeCycle event, final Throwable cause) {
      //because this method can be called if there is a failure on shutdown
      //only attempt to signal failure if the failure is on startup
      if (!(event.isStopping() || event.isStopped())) {
        sendSignal(cfSignalResourceConfig, instanceId, false);
      }
    }

    @Override
    public void lifeCycleStopping(final LifeCycle event) {
      //dont care
    }

    @Override
    public void lifeCycleStopped(final LifeCycle event) {
      //dont care
    }

    @Override
    public void lifeCycleStarted(final LifeCycle event) {
      sendSignal(cfSignalResourceConfig, instanceId, true);
    }
  }
}