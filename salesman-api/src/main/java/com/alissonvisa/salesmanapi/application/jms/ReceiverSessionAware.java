package com.alissonvisa.salesmanapi.application.jms;

import com.alissonvisa.salesmanapi.domain.Sale;
import com.alissonvisa.salesmanapi.domain.service.SalesmanService;
import lombok.extern.log4j.Log4j2;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.listener.SessionAwareMessageListener;
import org.springframework.stereotype.Component;

import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

@Log4j2
@Component
public class ReceiverSessionAware implements SessionAwareMessageListener<TextMessage> {

    private static final String FINISH_FILE_QUEUE = "finish_file_queue";

    private final SalesmanService salesmanService;

    @Autowired
    public ReceiverSessionAware(SalesmanService salesmanService) {
        this.salesmanService = salesmanService;
    }

    @Override
    @JmsListener(destination = FINISH_FILE_QUEUE)
    public void onMessage(TextMessage message, Session session) throws JMSException {
        log.info("finish queue received message='{}'", message.getText());
        final Long countSalesman = salesmanService.countSalesmanByArchive(message.getText());

        final TextMessage responseMessage = new ActiveMQTextMessage();
        responseMessage.setJMSCorrelationID(message.getJMSCorrelationID());
        responseMessage.setText(String.format("Quantidade de vendedor no arquivo de entrada=%s", countSalesman));

        final MessageProducer producer = session.createProducer(message.getJMSReplyTo());
        producer.send(responseMessage);
    }
}
