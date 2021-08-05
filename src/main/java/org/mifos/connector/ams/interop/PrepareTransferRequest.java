package org.mifos.connector.ams.interop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.connector.ams.zeebe.ZeebeUtil;
import org.mifos.connector.common.ams.dto.TransferFspRequestDTO;
import org.mifos.connector.common.mojaloop.dto.Extension;
import org.mifos.connector.common.mojaloop.dto.ExtensionList;
import org.mifos.connector.common.mojaloop.dto.FspMoneyData;
import org.mifos.connector.common.mojaloop.dto.TransactionType;
import org.mifos.connector.common.mojaloop.type.InitiatorType;
import org.mifos.connector.common.mojaloop.type.Scenario;
import org.mifos.connector.common.mojaloop.type.TransactionRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

import static org.mifos.connector.ams.camel.config.CamelProperties.TRANSACTION_ROLE;
import static org.mifos.connector.ams.zeebe.ZeebeUtil.zeebeVariable;
import static org.mifos.connector.ams.zeebe.ZeebeVariables.BOOK_TRANSACTION_ID;
import static org.mifos.connector.ams.zeebe.ZeebeVariables.EXTERNAL_ACCOUNT_ID;
import static org.mifos.connector.ams.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.ams.zeebe.ZeebeVariables.TRANSFER_CODE;

@Component
@ConditionalOnExpression("${ams.local.enabled}")
public class PrepareTransferRequest implements Processor {
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void process(Exchange exchange) throws Exception {
        TransactionType transactionType = new TransactionType();
        transactionType.setInitiator(TransactionRole.valueOf(zeebeVariable(exchange, "initiator", String.class)));
        transactionType.setInitiatorType(InitiatorType.valueOf(zeebeVariable(exchange, "initiatorType", String.class)));
        transactionType.setScenario(Scenario.valueOf(zeebeVariable(exchange, "scenario", String.class)));

        String note = zeebeVariable(exchange, "note", String.class);
        FspMoneyData amount = zeebeVariable(exchange, "amount", FspMoneyData.class);
        FspMoneyData fspFee = zeebeVariable(exchange, "fspFee", FspMoneyData.class);
        FspMoneyData fspCommission = zeebeVariable(exchange, "fspCommission", FspMoneyData.class);

        String existingTransferCode = exchange.getProperty(TRANSFER_CODE, String.class);
        String transferCode;
        if (existingTransferCode != null) {
            transferCode = existingTransferCode;
        } else {
            transferCode = UUID.randomUUID().toString();
            exchange.setProperty(TRANSFER_CODE, transferCode);
        }

        String transactionCode = exchange.getProperty(BOOK_TRANSACTION_ID, String.class) != null ?
                exchange.getProperty(BOOK_TRANSACTION_ID, String.class) : exchange.getProperty(TRANSACTION_ID, String.class);
        logger.debug("using transaction code {}", transactionCode);

        TransferFspRequestDTO transferRequestDTO = null;

        if (fspFee != null || fspCommission != null) {
            transferRequestDTO = new TransferFspRequestDTO(
                transactionCode,
                transferCode,
                exchange.getProperty(EXTERNAL_ACCOUNT_ID, String.class),
                amount,
                fspFee,
                fspCommission,
                TransactionRole.valueOf(exchange.getProperty(TRANSACTION_ROLE, String.class)),
                transactionType,
                note);
        } else {
            transferRequestDTO = new TransferFspRequestDTO(
                transactionCode,
                transferCode,
                exchange.getProperty(EXTERNAL_ACCOUNT_ID, String.class),
                amount,
                TransactionRole.valueOf(exchange.getProperty(TRANSACTION_ROLE, String.class)));
        }

        logger.debug("prepared transferRequestDTO: {}", objectMapper.writeValueAsString(transferRequestDTO));
        exchange.getIn().setBody(transferRequestDTO);
    }
}
