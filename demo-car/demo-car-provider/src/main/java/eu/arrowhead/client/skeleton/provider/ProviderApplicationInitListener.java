package eu.arrowhead.client.skeleton.provider;

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;

import eu.arrowhead.common.exception.UnavailableServerException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import ai.aitia.demo.car_provider.CarProviderConstants;
import eu.arrowhead.client.library.ArrowheadService;
import eu.arrowhead.client.library.config.ApplicationInitListener;
import eu.arrowhead.client.library.util.ClientCommonConstants;
import eu.arrowhead.client.skeleton.provider.security.ProviderSecurityConfig;
import eu.arrowhead.common.CommonConstants;
import eu.arrowhead.common.Utilities;
import eu.arrowhead.common.core.CoreSystem;
import eu.arrowhead.common.dto.shared.ServiceRegistryRequestDTO;
import eu.arrowhead.common.dto.shared.ServiceSecurityType;
import eu.arrowhead.common.dto.shared.SystemRequestDTO;
import eu.arrowhead.common.exception.ArrowheadException;

@Component
public class ProviderApplicationInitListener extends ApplicationInitListener {

	//=================================================================================================
	// members

	@Autowired
	private ArrowheadService arrowheadService;

	@Autowired
	private ProviderSecurityConfig providerSecurityConfig;

	@Value(ClientCommonConstants.$TOKEN_SECURITY_FILTER_ENABLED_WD)
	private boolean tokenSecurityFilterEnabled;

	@Value(CommonConstants.$SERVER_SSL_ENABLED_WD)
	private boolean sslEnabled;

	@Value(ClientCommonConstants.$CLIENT_SYSTEM_NAME)
	private String mySystemName;

	@Value(ClientCommonConstants.$CLIENT_SERVER_ADDRESS_WD)
	private String mySystemAddress;

	@Value(ClientCommonConstants.$CLIENT_SERVER_PORT_WD)
	private int mySystemPort;

	private final Logger logger = LogManager.getLogger(ProviderApplicationInitListener.class);

	//=================================================================================================
	// methods

    @Autowired
    private TaskExecutor taskExecutor;

	//-------------------------------------------------------------------------------------------------
	@Override
	protected void customInit(final ContextRefreshedEvent event) {
	    try {
            //Checking the availability of necessary core systems
            checkCoreSystemReachability(CoreSystem.SERVICE_REGISTRY);
            if (sslEnabled && tokenSecurityFilterEnabled) {
                checkCoreSystemReachability(CoreSystem.AUTHORIZATION);

                //Initialize Arrowhead Context
                arrowheadService.updateCoreServiceURIs(CoreSystem.AUTHORIZATION);

                setTokenSecurityFilter();
            } else {
                logger.info("TokenSecurityFilter in not active");
            }


            //Register services into ServiceRegistry
            final ServiceRegistryRequestDTO createCarServiceRequest = createServiceRegistryRequest(CarProviderConstants.CREATE_CAR_SERVICE_DEFINITION, CarProviderConstants.CAR_URI, HttpMethod.POST);
            arrowheadService.forceRegisterServiceToServiceRegistry(createCarServiceRequest);

            ServiceRegistryRequestDTO getCarServiceRequest = createServiceRegistryRequest(CarProviderConstants.GET_CAR_SERVICE_DEFINITION,  CarProviderConstants.CAR_URI, HttpMethod.GET);
            getCarServiceRequest.getMetadata().put(CarProviderConstants.REQUEST_PARAM_KEY_BRAND, CarProviderConstants.REQUEST_PARAM_BRAND);
            getCarServiceRequest.getMetadata().put(CarProviderConstants.REQUEST_PARAM_KEY_COLOR, CarProviderConstants.REQUEST_PARAM_COLOR);
            arrowheadService.forceRegisterServiceToServiceRegistry(getCarServiceRequest);
        } catch (UnavailableServerException exception) {
            // to test without the presence of AHT framework
            exception.printStackTrace();
        }
        // exit after some time
        taskExecutor.execute(() -> {
            try {
                Thread.sleep(1_000);
                ((ConfigurableApplicationContext) event.getApplicationContext()).close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
	}

	//-------------------------------------------------------------------------------------------------
	@Override
	public void customDestroy() {
		//Unregister service
		arrowheadService.unregisterServiceFromServiceRegistry(CarProviderConstants.CREATE_CAR_SERVICE_DEFINITION);
	}

	//=================================================================================================
	// assistant methods

	//-------------------------------------------------------------------------------------------------
	private void setTokenSecurityFilter() {
		final PublicKey authorizationPublicKey = arrowheadService.queryAuthorizationPublicKey();
		if (authorizationPublicKey == null) {
			throw new ArrowheadException("Authorization public key is null");
		}

		KeyStore keystore;
		try {
			keystore = KeyStore.getInstance(sslProperties.getKeyStoreType());
			keystore.load(sslProperties.getKeyStore().getInputStream(), sslProperties.getKeyStorePassword().toCharArray());
		} catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException ex) {
			throw new ArrowheadException(ex.getMessage());
		}
		final PrivateKey providerPrivateKey = Utilities.getPrivateKey(keystore, sslProperties.getKeyPassword());

		providerSecurityConfig.getTokenSecurityFilter().setAuthorizationPublicKey(authorizationPublicKey);
		providerSecurityConfig.getTokenSecurityFilter().setMyPrivateKey(providerPrivateKey);

	}

	//-------------------------------------------------------------------------------------------------
	private ServiceRegistryRequestDTO createServiceRegistryRequest(final String serviceDefinition, final String serviceUri, final HttpMethod httpMethod) {
		final ServiceRegistryRequestDTO serviceRegistryRequest = new ServiceRegistryRequestDTO();
		serviceRegistryRequest.setServiceDefinition(serviceDefinition);
		final SystemRequestDTO systemRequest = new SystemRequestDTO();
		systemRequest.setSystemName(mySystemName);
		systemRequest.setAddress(mySystemAddress);
		systemRequest.setPort(mySystemPort);

		if (tokenSecurityFilterEnabled) {
			systemRequest.setAuthenticationInfo(Base64.getEncoder().encodeToString(arrowheadService.getMyPublicKey().getEncoded()));
			serviceRegistryRequest.setSecure(ServiceSecurityType.TOKEN.name());
			serviceRegistryRequest.setInterfaces(List.of(CarProviderConstants.INTERFACE_SECURE));
		} else if (sslEnabled) {
			systemRequest.setAuthenticationInfo(Base64.getEncoder().encodeToString(arrowheadService.getMyPublicKey().getEncoded()));
			serviceRegistryRequest.setSecure(ServiceSecurityType.CERTIFICATE.name());
			serviceRegistryRequest.setInterfaces(List.of(CarProviderConstants.INTERFACE_SECURE));
		} else {
			serviceRegistryRequest.setSecure(ServiceSecurityType.NOT_SECURE.name());
			serviceRegistryRequest.setInterfaces(List.of(CarProviderConstants.INTERFACE_INSECURE));
		}
		serviceRegistryRequest.setProviderSystem(systemRequest);
		serviceRegistryRequest.setServiceUri(serviceUri);
		serviceRegistryRequest.setMetadata(new HashMap<>());
		serviceRegistryRequest.getMetadata().put(CarProviderConstants.HTTP_METHOD, httpMethod.name());
		return serviceRegistryRequest;
	}
}
