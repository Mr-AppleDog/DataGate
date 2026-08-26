package org.dromara.db.resource.config;

import org.dromara.db.resource.credential.CredentialCryptoService;
import org.dromara.db.core.spi.KekProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 凭据保险箱装配
 *
 * @author DataGate
 */
@Configuration
public class CredentialVaultConfiguration {

    @Bean
    public CredentialCryptoService credentialCryptoService(KekProvider kekProvider) {
        return new CredentialCryptoService(kekProvider);
    }
}
