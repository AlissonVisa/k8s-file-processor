package com.alissonvisa.batchfilereader.configuration;

import com.alissonvisa.batchfilereader.batch.RawLineMapper;
import com.alissonvisa.batchfilereader.integration.FileMessageToJobRequest;
import com.alissonvisa.batchfilereader.messaging.MessageProducer;
import io.micrometer.core.instrument.util.StringUtils;
import lombok.extern.log4j.Log4j2;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.command.ActiveMQQueue;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.*;
import org.springframework.batch.core.launch.support.SimpleJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.integration.chunk.RemoteChunkingManagerStepBuilderFactory;
import org.springframework.batch.integration.config.annotation.EnableBatchIntegration;
import org.springframework.batch.integration.launch.JobLaunchingGateway;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.file.filters.SimplePatternFileListFilter;
import org.springframework.integration.handler.LoggingHandler;
import org.springframework.integration.jms.dsl.Jms;
import org.springframework.jms.core.JmsTemplate;

import javax.jms.TextMessage;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Configuration
@EnableBatchProcessing
@EnableBatchIntegration
@EnableIntegration
@Log4j2
public class JobManagerConfiguration {

    private static final String WILL_BE_INJECTED = null;
    public static final String DATA_IN = "/data/in";
    public static final String DATA_OUT = "/data/out";

    @Value("${broker.url}")
    private String brokerUrl;

    @Value("${jms.responseQueue}")
    private String responseQueue;

    @Value("${jms.finishFileSalesmanQueue}")
    private String finishFileSalesmanQueue;

    @Value("${jms.finishFileSalesQueue}")
    private String finishFileSalesQueue;

    @Value("${jms.finishFileCustomerQueue}")
    private String finishFileCustomerQueue;

    @Value("${homepath.dir:/app/file-input}")
    private String homepath;

    @Value("${chunk.size:50}")
    private String chunkSize;

    private final JobBuilderFactory jobBuilderFactory;

    private final RemoteChunkingManagerStepBuilderFactory managerStepBuilderFactory;

    private final StepBuilderFactory stepBuilderFactory;

    private final JobRepository jobRepository;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    public JobManagerConfiguration(JobBuilderFactory jobBuilderFactory,
                                   RemoteChunkingManagerStepBuilderFactory managerStepBuilderFactory,
                                   StepBuilderFactory stepBuilderFactory,
                                   JobRepository jobRepository) {
        this.jobBuilderFactory = jobBuilderFactory;
        this.managerStepBuilderFactory = managerStepBuilderFactory;
        this.stepBuilderFactory = stepBuilderFactory;
        this.jobRepository = jobRepository;
    }

    @Bean
    public ActiveMQConnectionFactory connectionFactory() {
        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory();
        connectionFactory.setBrokerURL(this.brokerUrl);
        connectionFactory.setTrustAllPackages(true);
        return connectionFactory;
    }

    /*
     * Configure outbound flow (requests going to workers)
     */
    @Bean
    public DirectChannel requests() {
        return new DirectChannel();
    }

    @Bean
    public IntegrationFlow outboundFlow(ActiveMQConnectionFactory connectionFactory) {
        return IntegrationFlows
                .from(requests())
                .handle(Jms.outboundAdapter(connectionFactory).destination("requests"))
                .get();
    }

    /*
     * Configure inbound flow (replies coming from workers)
     */
    @Bean
    public QueueChannel replies() {
        return new QueueChannel();
    }

    @Bean
    public IntegrationFlow inboundFlow(ActiveMQConnectionFactory connectionFactory) {
        return IntegrationFlows
                .from(Jms.messageDrivenChannelAdapter(connectionFactory).destination("replies"))
                .channel(replies())
                .get();
    }

    @Bean
    @StepScope
    public FlatFileItemReader sampleReader(@Value("#{jobParameters[input_file_name]}") String resource) {
        if(StringUtils.isEmpty(resource)) {
            throw new FileSystemNotFoundException(resource);
        }
        final FileSystemResource fileSystemResource = new FileSystemResource(resource);
        FlatFileItemReader flatFileItemReader = new FlatFileItemReader();
        flatFileItemReader.setResource(fileSystemResource);
        flatFileItemReader.setLineMapper(new RawLineMapper(fileSystemResource.getFilename()));
        return flatFileItemReader;
    }

    @Bean
    public TaskletStep managerStep() {
        return this.managerStepBuilderFactory.get("managerStep")
                .chunk(Integer.valueOf(this.chunkSize))
                .reader(sampleReader(WILL_BE_INJECTED))
                .outputChannel(requests())
                .inputChannel(replies())
                .skip(FileSystemNotFoundException.class)
                .build();
    }

    @Bean
    @JobScope
    public Step deletingStep(@Value("#{jobParameters[input_file_name]}") String resource) {
        Tasklet step = (stepContribution, chunkContext) -> {
            try {
                java.nio.file.Files.deleteIfExists(Paths.get(resource));
            } catch(Exception e) {
                log.error(e.getMessage() + " Path: " + resource, e);
            }
            log.info("Deletion successful. Path: " + resource);
            return RepeatStatus.FINISHED;
        };
        return this.stepBuilderFactory.get("deletingStep").tasklet(step).build();
    }

    @Bean
    @JobScope
    public Step writeOutputStep(@Value("#{jobParameters[input_file_name]}") String resource) {
        String filename = getDoneFileName(resource);
        Path donePath = Paths.get(this.homepath + DATA_OUT);
        Path doneFile = Paths.get(donePath + "/" + filename);
        Tasklet step = (stepContribution, chunkContext) -> writeOutDoneFile(resource, donePath, doneFile);
        return this.stepBuilderFactory.get("writeOutputStep").tasklet(step).build();
    }

    private RepeatStatus writeOutDoneFile(String resource, Path donePath, Path doneFile) {
        createDirectories(donePath);
        final TextMessage responseSalesman = sendAndReceive(resource, finishFileSalesmanQueue);
        final TextMessage responseCustomer = sendAndReceive(resource, finishFileCustomerQueue);
        final TextMessage responseSales = sendAndReceive(resource, finishFileSalesQueue);
        createDoneFile(doneFile, responseSalesman, responseCustomer, responseSales);
        return RepeatStatus.FINISHED;
    }

    private void createDirectories(Path donePath) {
        try {
            java.nio.file.Files.createDirectories(donePath);
        } catch(Exception e) {
            log.error(e.getMessage() + " Path: " + donePath.toString(), e);
        }
    }

    private TextMessage sendAndReceive(String resource, String queue) {
        return messageProducerAndReceive().sendAndReceiveMessage(queue, Paths.get(resource).getFileName().toString());
    }

    private void createDoneFile(Path doneFile,
                                TextMessage responseSalesman,
                                TextMessage responseCustomer,
                                TextMessage responseSales) {
        try {
            java.nio.file.Files.createFile(doneFile);
            Writer output;
            output = new BufferedWriter(new FileWriter(doneFile.toString()));
            output.append(responseCustomer.getText() + "\n");
            output.append(responseSalesman.getText() + "\n");
            output.append(responseSales.getText());
            output.close();
            log.info("Done file successful. Path: " + doneFile.toString());
        } catch(Exception e) {
            log.error(e.getMessage() + " Path: " + doneFile.toString(), e);
        }
    }

    private String getDoneFileName(String resource) {
        return Paths.get(resource).getFileName().toString().replace(".dat", ".done.dat");
    }

    @Bean
    public Job remoteChunkingJob() {
        return this.jobBuilderFactory.get("remoteChunkingJob")
                .start(managerStep())
                .next(deletingStep(WILL_BE_INJECTED))
                .next(writeOutputStep(WILL_BE_INJECTED))
                .build();
    }

    @Bean()
    public FileMessageToJobRequest fileMessageToJobRequest() {
        FileMessageToJobRequest fileMessageToJobRequest = new FileMessageToJobRequest();
        fileMessageToJobRequest.setFileParameterName("input_file_name");
        fileMessageToJobRequest.setJob(remoteChunkingJob());
        return fileMessageToJobRequest;
    }

    @Bean
    public JobLaunchingGateway jobLaunchingGateway() {
        SimpleJobLauncher simpleJobLauncher = new SimpleJobLauncher();
        simpleJobLauncher.setJobRepository(jobRepository);
        simpleJobLauncher.setTaskExecutor(new SyncTaskExecutor());
        JobLaunchingGateway jobLaunchingGateway = new JobLaunchingGateway(simpleJobLauncher);

        return jobLaunchingGateway;
    }

    @Bean
    public IntegrationFlow integrationFlow(JobLaunchingGateway jobLaunchingGateway) throws IOException {
        log.info("CHUNK_SIZE=" + this.chunkSize);
        log.info("HOMEPATH=" + this.homepath);
        Path pathFile = createFileDirectories(DATA_IN);
        createFileDirectories(DATA_OUT);
        return IntegrationFlows.from(
                    org.springframework.integration.file.dsl.Files.inboundAdapter(pathFile.toFile())
                            .autoCreateDirectory(true)
                            .filter(new SimplePatternFileListFilter("*.dat"))
                            .useWatchService(true)
                            .preventDuplicates(true)
                            .scanEachPoll(true),
                    c -> c.poller(Pollers.fixedRate(3000).maxMessagesPerPoll(1)).autoStartup(true))
                .handle(fileMessageToJobRequest())
                .handle(jobLaunchingGateway)
                .log(LoggingHandler.Level.WARN, "headers.id + ': ' + payload")
                .get();
    }

    private Path createFileDirectories(String fileDirectory) throws IOException {
        Path pathFile = Paths.get(this.homepath + fileDirectory);
        if(Files.notExists(pathFile)) {
            Files.createDirectories(pathFile);
        }
        return pathFile;
    }

    @Bean
    public MessageProducer messageProducerAndReceive() {
        return (queueName, message) -> {
            if(StringUtils.isEmpty(queueName)) {
                throw new IllegalArgumentException("Queue must not be empty");
            }
            log.debug("Sending message " + message + "to queue - " + queueName);

            return (TextMessage) jmsTemplate.sendAndReceive(queueName, session -> {
                TextMessage messageToSend = session.createTextMessage();
                messageToSend.setText(message);
                messageToSend.setJMSCorrelationID(UUID.randomUUID().toString());
                messageToSend.setJMSReplyTo(new ActiveMQQueue(responseQueue));
                return messageToSend;
            });
        };
    }

}
