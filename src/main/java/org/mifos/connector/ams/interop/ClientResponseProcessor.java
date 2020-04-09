package org.mifos.connector.ams.interop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.connector.ams.properties.TenantProperties;
import org.mifos.phee.common.ams.dto.ClientData;
import org.mifos.phee.common.ams.dto.Customer;
import org.mifos.phee.common.ams.dto.LegalForm;
import org.mifos.phee.common.mojaloop.dto.ComplexName;
import org.mifos.phee.common.mojaloop.dto.Party;
import org.mifos.phee.common.mojaloop.dto.PartyIdInfo;
import org.mifos.phee.common.mojaloop.dto.PersonalInfo;
import org.mifos.phee.common.mojaloop.type.IdentifierType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.ams.camel.config.CamelProperties.PARTY_ID;
import static org.mifos.connector.ams.camel.config.CamelProperties.PARTY_ID_TYPE;
import static org.mifos.connector.ams.camel.config.CamelProperties.PAYEE_PARTY_RESPONSE;
import static org.mifos.connector.ams.camel.config.CamelProperties.ZEEBE_JOB_KEY;
import static org.mifos.phee.common.ams.dto.LegalForm.PERSON;

@Component
@ConditionalOnExpression("${ams.local.enabled}")
public class ClientResponseProcessor implements Processor {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${ams.local.version}")
    private String amsVersion;

    @Autowired
    private TenantProperties tenantProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ZeebeClient zeebeClient;

    @Override
    public void process(Exchange exchange) throws Exception {
        Integer responseCode = exchange.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
        String partyIdType = exchange.getProperty(PARTY_ID_TYPE, String.class);
        String partyId = exchange.getProperty(PARTY_ID, String.class);

        if (responseCode > 202) {
            String errorMsg = String.format("Invalid responseCode %s for payee-party-lookup, partyIdType: %s partyId: %s Message: %s",
                    responseCode,
                    partyIdType,
                    partyId,
                    exchange.getIn().getBody(String.class));

            logger.error(errorMsg);

            zeebeClient.newThrowErrorCommand(exchange.getProperty(ZEEBE_JOB_KEY, Long.class))
                    .errorCode(ZeebeErrorCode.PAYEE_PARTY_LOOKUP_ERROR)
                    .errorMessage(errorMsg)
                    .send();
        } else {
            Party mojaloopParty = new Party(
                    new PartyIdInfo(IdentifierType.valueOf(partyIdType),
                            partyId,
                            null,
                            tenantProperties.getTenant(partyIdType, partyId).getFspId()),
                    null,
                    null,
                    null);

            if ("1.2".equals(amsVersion)) {
                ClientData client = exchange.getIn().getBody(ClientData.class);
                if (PERSON.equals(LegalForm.fromValue(client.getId().intValue()))) {
                    PersonalInfo pi = new PersonalInfo();
                    pi.setDateOfBirth(client.getDateOfBirth() != null ? client.getDateOfBirth().toString() : null);
                    ComplexName cn = new ComplexName();
                    cn.setFirstName(client.getFirstname());
                    cn.setLastName(client.getLastname());
                    cn.setMiddleName(client.getMiddlename());
                    pi.setComplexName(cn);
                    mojaloopParty.setPersonalInfo(pi);
                } else { // entity
                    mojaloopParty.setName(client.getFullname());
                }
            } else { // cn
                Customer client = exchange.getIn().getBody(Customer.class);
                if (PERSON.equals(client.getType())) {
                    PersonalInfo pi = new PersonalInfo();
                    pi.setDateOfBirth(client.getDateOfBirth() != null ? client.getDateOfBirth().toString() : null);
                    ComplexName cn = new ComplexName();
                    cn.setFirstName(client.getGivenName());
                    cn.setLastName(client.getSurname());
                    cn.setMiddleName(client.getMiddleName());
                    pi.setComplexName(cn);
                    mojaloopParty.setPersonalInfo(pi);
                } else {
                    mojaloopParty.setName(client.getGivenName());
                }
            }

            Map<String, Object> variables = new HashMap<>();
            variables.put(PAYEE_PARTY_RESPONSE, objectMapper.writeValueAsString(mojaloopParty));
            zeebeClient.newCompleteCommand(exchange.getProperty(ZEEBE_JOB_KEY, Long.class))
                    .variables(variables)
                    .send();
        }
    }
}