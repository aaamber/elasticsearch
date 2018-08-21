/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.rollup.action;


import org.apache.logging.log4j.Logger;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexAction;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsAction;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingAction;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.persistent.PersistentTasksService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.rollup.ConfigTestHelpers;
import org.elasticsearch.xpack.core.rollup.RollupField;
import org.elasticsearch.xpack.core.rollup.job.RollupJob;
import org.elasticsearch.xpack.core.rollup.job.RollupJobConfig;
import org.elasticsearch.xpack.rollup.Rollup;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PutJobStateMachineTests extends ESTestCase {

    @SuppressWarnings("unchecked")
    public void testCreateIndexException() {
        RollupJob job = new RollupJob(ConfigTestHelpers.randomRollupJobConfig(random(), "foo"), Collections.emptyMap());

        ActionListener<AcknowledgedResponse> testListener = ActionListener.wrap(response -> {
            fail("Listener success should not have been triggered.");
        }, e -> {
            assertThat(e.getMessage(), equalTo("Could not create index for rollup job [foo]"));
            assertThat(e.getCause().getMessage(), equalTo("something bad"));
        });

        Logger logger = mock(Logger.class);
        Client client = mock(Client.class);

        ArgumentCaptor<ActionListener> requestCaptor = ArgumentCaptor.forClass(ActionListener.class);
        doAnswer(invocation -> {
            requestCaptor.getValue().onFailure(new RuntimeException("something bad"));
            return null;
        }).when(client).execute(eq(CreateIndexAction.INSTANCE), any(CreateIndexRequest.class), requestCaptor.capture());

        TransportPutRollupJobAction.createIndex(job, testListener, mock(PersistentTasksService.class), client, logger);

        // ResourceAlreadyExists should trigger a GetMapping next
        verify(client).execute(eq(CreateIndexAction.INSTANCE), any(CreateIndexRequest.class), any());
    }

    @SuppressWarnings("unchecked")
    public void testIndexAlreadyExists() {
        RollupJob job = new RollupJob(ConfigTestHelpers.randomRollupJobConfig(random()), Collections.emptyMap());

        ActionListener<AcknowledgedResponse> testListener = ActionListener.wrap(response -> {
            fail("Listener success should not have been triggered.");
        }, e -> {
            assertThat(e.getCause().getMessage(), equalTo("Ending"));
        });

        Logger logger = mock(Logger.class);
        Client client = mock(Client.class);

        ArgumentCaptor<ActionListener> requestCaptor = ArgumentCaptor.forClass(ActionListener.class);
        doAnswer(invocation -> {
            requestCaptor.getValue().onFailure(new ResourceAlreadyExistsException(job.getConfig().getRollupIndex()));
            return null;
        }).when(client).execute(eq(CreateIndexAction.INSTANCE), any(CreateIndexRequest.class), requestCaptor.capture());

        ArgumentCaptor<ActionListener> requestCaptor2 = ArgumentCaptor.forClass(ActionListener.class);
        doAnswer(invocation -> {
            // Bail here with an error, further testing will happen through tests of #updateMapping
            requestCaptor2.getValue().onFailure(new RuntimeException("Ending"));
            return null;
        }).when(client).execute(eq(GetMappingsAction.INSTANCE), any(GetMappingsRequest.class), requestCaptor2.capture());

        TransportPutRollupJobAction.createIndex(job, testListener, mock(PersistentTasksService.class), client, logger);

        // ResourceAlreadyExists should trigger a GetMapping next
        verify(client).execute(eq(GetMappingsAction.INSTANCE), any(GetMappingsRequest.class), any());
    }

    @SuppressWarnings("unchecked")
    public void testIndexMetaData() throws InterruptedException {
        RollupJob job = new RollupJob(ConfigTestHelpers.randomRollupJobConfig(random()), Collections.emptyMap());

        ActionListener<AcknowledgedResponse> testListener = ActionListener.wrap(response -> {
            fail("Listener success should not have been triggered.");
        }, e -> {
            assertThat(e.getCause().getMessage(), equalTo("Ending"));
        });

        Logger logger = mock(Logger.class);
        Client client = mock(Client.class);

        CountDownLatch latch = new CountDownLatch(1);
        ArgumentCaptor<ActionListener> listenerCaptor = ArgumentCaptor.forClass(ActionListener.class);
        ArgumentCaptor<CreateIndexRequest> requestCaptor = ArgumentCaptor.forClass(CreateIndexRequest.class);
        doAnswer(invocation -> {
            assertNotNull(requestCaptor.getValue().mappings().get("_doc"));
            String mapping = requestCaptor.getValue().mappings().get("_doc");

            // Make sure the version is present, and we have our date template (the most important aspects)
            assertThat(mapping, containsString("\"rollup-version\": \"" + Version.CURRENT.toString() + "\""));
            assertThat(mapping, containsString("\"path_match\": \"*.date_histogram.timestamp\""));

            listenerCaptor.getValue().onFailure(new ResourceAlreadyExistsException(job.getConfig().getRollupIndex()));
            latch.countDown();
            return null;
        }).when(client).execute(eq(CreateIndexAction.INSTANCE), requestCaptor.capture(), listenerCaptor.capture());

        ArgumentCaptor<ActionListener> requestCaptor2 = ArgumentCaptor.forClass(ActionListener.class);
        doAnswer(invocation -> {
            // Bail here with an error, further testing will happen through tests of #updateMapping
            requestCaptor2.getValue().onFailure(new RuntimeException("Ending"));
            return null;
        }).when(client).execute(eq(GetMappingsAction.INSTANCE), any(GetMappingsRequest.class), requestCaptor2.capture());

        TransportPutRollupJobAction.createIndex(job, testListener, mock(PersistentTasksService.class), client, logger);

        // ResourceAlreadyExists should trigger a GetMapping next
        verify(client).execute(eq(GetMappingsAction.INSTANCE), any(GetMappingsRequest.class), any());
        latch.await(4, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    public void testGetMappingFails() {
        RollupJob job = new RollupJob(ConfigTestHelpers.randomRollupJobConfig(random(), "foo"), Collections.emptyMap());

        ActionListener<AcknowledgedResponse> testListener = ActionListener.wrap(response -> {
            fail("Listener success should not have been triggered.");
        }, e -> {
            assertThat(e.getMessage(), equalTo("Could not update mappings for rollup job [foo]"));
            assertThat(e.getCause().getMessage(), equalTo("something bad"));
        });

        Logger logger = mock(Logger.class);
        Client client = mock(Client.class);

        ArgumentCaptor<ActionListener> requestCaptor = ArgumentCaptor.forClass(ActionListener.class);
        doAnswer(invocation -> {
            requestCaptor.getValue().onFailure(new RuntimeException("something bad"));
            return null;
        }).when(client).execute(eq(GetMappingsAction.INSTANCE), any(GetMappingsRequest.class), requestCaptor.capture());

        TransportPutRollupJobAction.updateMapping(job, testListener, mock(PersistentTasksService.class), client, logger);
        verify(client).execute(eq(GetMappingsAction.INSTANCE), any(GetMappingsRequest.class), any());
    }

    @SuppressWarnings("unchecked")
    public void testNoMetadataInMapping() {
        RollupJob job = new RollupJob(ConfigTestHelpers.randomRollupJobConfig(random()), Collections.emptyMap());

        ActionListener<AcknowledgedResponse> testListener = ActionListener.wrap(response -> {
            fail("Listener success should not have been triggered.");
        }, e -> {
            assertThat(e.getMessage(), equalTo("Expected to find _meta key in mapping of rollup index ["
                + job.getConfig().getRollupIndex() + "] but not found."));
        });

        Logger logger = mock(Logger.class);
        Client client = mock(Client.class);

        ArgumentCaptor<ActionListener> requestCaptor = ArgumentCaptor.forClass(ActionListener.class);
        doAnswer(invocation -> {
            GetMappingsResponse response = mock(GetMappingsResponse.class);
            MappingMetaData meta = new MappingMetaData(RollupField.TYPE_NAME, Collections.emptyMap());
            ImmutableOpenMap.Builder<String, MappingMetaData> builder = ImmutableOpenMap.builder(1);
            builder.put(RollupField.TYPE_NAME, meta);

            ImmutableOpenMap.Builder<String, ImmutableOpenMap<String, MappingMetaData>> builder2 = ImmutableOpenMap.builder(1);
            builder2.put(job.getConfig().getRollupIndex(), builder.build());

            when(response.getMappings()).thenReturn(builder2.build());
            requestCaptor.getValue().onResponse(response);
            return null;
        }).when(client).execute(eq(GetMappingsAction.INSTANCE), any(GetMappingsRequest.class), requestCaptor.capture());

        TransportPutRollupJobAction.updateMapping(job, testListener, mock(PersistentTasksService.class), client, logger);
        verify(client).execute(eq(GetMappingsAction.INSTANCE), any(GetMappingsRequest.class), any());
    }

    @SuppressWarnings("unchecked")
    public void testNoMappingVersion() {
        RollupJob job = new RollupJob(ConfigTestHelpers.randomRollupJobConfig(random()), Collections.emptyMap());

        ActionListener<AcknowledgedResponse> testListener = ActionListener.wrap(response -> {
            fail("Listener success should not have been triggered.");
        }, e -> {
            assertThat(e.getMessage(), equalTo("Could not determine version of existing rollup metadata for index ["
                + job.getConfig().getRollupIndex() + "]"));
        });

        Logger logger = mock(Logger.class);
        Client client = mock(Client.class);

        ArgumentCaptor<ActionListener> requestCaptor = ArgumentCaptor.forClass(ActionListener.class);
        doAnswer(invocation -> {
            GetMappingsResponse response = mock(GetMappingsResponse.class);
            Map<String, Object> m = new HashMap<>(2);
            m.put(RollupField.ROLLUP_META,
                Collections.singletonMap(job.getConfig().getId(), job.getConfig()));
            MappingMetaData meta = new MappingMetaData(RollupField.TYPE_NAME,
                Collections.singletonMap("_meta", m));
            ImmutableOpenMap.Builder<String, MappingMetaData> builder = ImmutableOpenMap.builder(1);
            builder.put(RollupField.TYPE_NAME, meta);

            ImmutableOpenMap.Builder<String, ImmutableOpenMap<String, MappingMetaData>> builder2 = ImmutableOpenMap.builder(1);
            builder2.put(job.getConfig().getRollupIndex(), builder.build());

            when(response.getMappings()).thenReturn(builder2.build());
            requestCaptor.getValue().onResponse(response);
            return null;
        }).when(client).execute(eq(GetMappingsAction.INSTANCE), any(GetMappingsRequest.class), requestCaptor.capture());

        TransportPutRollupJobAction.updateMapping(job, testListener, mock(PersistentTasksService.class), client, logger);
        verify(client).execute(eq(GetMappingsAction.INSTANCE), any(GetMappingsRequest.class), any());
    }

    @SuppressWarnings("unchecked")
    public void testJobAlreadyInMapping() {
        RollupJob job = new RollupJob(ConfigTestHelpers.randomRollupJobConfig(random(), "foo"), Collections.emptyMap());

        ActionListener<AcknowledgedResponse> testListener = ActionListener.wrap(response -> {
            fail("Listener success should not have been triggered.");
        }, e -> {
            assertThat(e.getMessage(), equalTo("Cannot create rollup job [foo] because job was previously created (existing metadata)."));
        });

        Logger logger = mock(Logger.class);
        Client client = mock(Client.class);

        ArgumentCaptor<ActionListener> requestCaptor = ArgumentCaptor.forClass(ActionListener.class);
        doAnswer(invocation -> {
            GetMappingsResponse response = mock(GetMappingsResponse.class);
            Map<String, Object> m = new HashMap<>(2);
            m.put(Rollup.ROLLUP_TEMPLATE_VERSION_FIELD, Version.V_6_4_0);
            m.put(RollupField.ROLLUP_META,
                Collections.singletonMap(job.getConfig().getId(), job.getConfig()));
            MappingMetaData meta = new MappingMetaData(RollupField.TYPE_NAME,
                Collections.singletonMap("_meta", m));
            ImmutableOpenMap.Builder<String, MappingMetaData> builder = ImmutableOpenMap.builder(1);
            builder.put(RollupField.TYPE_NAME, meta);

            ImmutableOpenMap.Builder<String, ImmutableOpenMap<String, MappingMetaData>> builder2 = ImmutableOpenMap.builder(1);
            builder2.put(job.getConfig().getRollupIndex(), builder.build());

            when(response.getMappings()).thenReturn(builder2.build());
            requestCaptor.getValue().onResponse(response);
            return null;
        }).when(client).execute(eq(GetMappingsAction.INSTANCE), any(GetMappingsRequest.class), requestCaptor.capture());

        TransportPutRollupJobAction.updateMapping(job, testListener, mock(PersistentTasksService.class), client, logger);
        verify(client).execute(eq(GetMappingsAction.INSTANCE), any(GetMappingsRequest.class), any());
    }

    @SuppressWarnings("unchecked")
    public void testAddJobToMapping() {
        final RollupJobConfig unrelatedJob =
            ConfigTestHelpers.randomRollupJobConfig(random(), ESTestCase.randomAlphaOfLength(10), "foo", "rollup_index_foo");

        final RollupJobConfig config =
            ConfigTestHelpers.randomRollupJobConfig(random(), ESTestCase.randomAlphaOfLength(10), "foo", "rollup_index_foo");
        RollupJob job = new RollupJob(config, Collections.emptyMap());
        ActionListener<AcknowledgedResponse> testListener = ActionListener.wrap(response -> {
            fail("Listener success should not have been triggered.");
        }, e -> {
            assertThat(e.getMessage(), equalTo("Ending"));
        });

        Logger logger = mock(Logger.class);
        Client client = mock(Client.class);

        ArgumentCaptor<ActionListener> requestCaptor = ArgumentCaptor.forClass(ActionListener.class);
        doAnswer(invocation -> {
            GetMappingsResponse response = mock(GetMappingsResponse.class);
            Map<String, Object> m = new HashMap<>(2);
            m.put(Rollup.ROLLUP_TEMPLATE_VERSION_FIELD, Version.V_6_4_0);
            m.put(RollupField.ROLLUP_META,
                Collections.singletonMap(unrelatedJob.getId(), unrelatedJob));
            MappingMetaData meta = new MappingMetaData(RollupField.TYPE_NAME,
                Collections.singletonMap("_meta", m));
            ImmutableOpenMap.Builder<String, MappingMetaData> builder = ImmutableOpenMap.builder(1);
            builder.put(RollupField.TYPE_NAME, meta);

            ImmutableOpenMap.Builder<String, ImmutableOpenMap<String, MappingMetaData>> builder2 = ImmutableOpenMap.builder(1);
            builder2.put(unrelatedJob.getRollupIndex(), builder.build());

            when(response.getMappings()).thenReturn(builder2.build());
            requestCaptor.getValue().onResponse(response);
            return null;
        }).when(client).execute(eq(GetMappingsAction.INSTANCE), any(GetMappingsRequest.class), requestCaptor.capture());

        ArgumentCaptor<ActionListener> requestCaptor2 = ArgumentCaptor.forClass(ActionListener.class);
        doAnswer(invocation -> {
            // Bail here with an error, further testing will happen through tests of #startPersistentTask
            requestCaptor2.getValue().onFailure(new RuntimeException("Ending"));
            return null;
        }).when(client).execute(eq(PutMappingAction.INSTANCE), any(PutMappingRequest.class), requestCaptor2.capture());

        TransportPutRollupJobAction.updateMapping(job, testListener, mock(PersistentTasksService.class), client, logger);
        verify(client).execute(eq(GetMappingsAction.INSTANCE), any(GetMappingsRequest.class), any());
        verify(client).execute(eq(PutMappingAction.INSTANCE), any(PutMappingRequest.class), any());
    }

    @SuppressWarnings("unchecked")
    public void testTaskAlreadyExists() {
        RollupJob job = new RollupJob(ConfigTestHelpers.randomRollupJobConfig(random(), "foo"), Collections.emptyMap());

        ActionListener<AcknowledgedResponse> testListener = ActionListener.wrap(response -> {
            fail("Listener success should not have been triggered.");
        }, e -> {
            assertThat(e.getMessage(), equalTo("Cannot create job [foo] because it has already been created (task exists)"));
        });

        PersistentTasksService tasksService = mock(PersistentTasksService.class);

        ArgumentCaptor<ActionListener> requestCaptor = ArgumentCaptor.forClass(ActionListener.class);
        doAnswer(invocation -> {
            requestCaptor.getValue().onFailure(new ResourceAlreadyExistsException(job.getConfig().getRollupIndex()));
            return null;
        }).when(tasksService).sendStartRequest(eq(job.getConfig().getId()),
            eq(RollupField.TASK_NAME), eq(job), requestCaptor.capture());

        TransportPutRollupJobAction.startPersistentTask(job, testListener, tasksService);
        verify(tasksService).sendStartRequest(eq(job.getConfig().getId()), eq(RollupField.TASK_NAME), eq(job), any());
    }

    @SuppressWarnings("unchecked")
    public void testStartTask() {
        RollupJob job = new RollupJob(ConfigTestHelpers.randomRollupJobConfig(random()), Collections.emptyMap());

        ActionListener<AcknowledgedResponse> testListener = ActionListener.wrap(response -> {
            fail("Listener success should not have been triggered.");
        }, e -> {
            assertThat(e.getMessage(), equalTo("Ending"));
        });

        PersistentTasksService tasksService = mock(PersistentTasksService.class);

        ArgumentCaptor<ActionListener> requestCaptor = ArgumentCaptor.forClass(ActionListener.class);
        doAnswer(invocation -> {
            PersistentTasksCustomMetaData.PersistentTask<RollupJob> response
                = new PersistentTasksCustomMetaData.PersistentTask<>(job.getConfig().getId(), RollupField.TASK_NAME, job, 123,
                mock(PersistentTasksCustomMetaData.Assignment.class));
            requestCaptor.getValue().onResponse(response);
            return null;
        }).when(tasksService).sendStartRequest(eq(job.getConfig().getId()), eq(RollupField.TASK_NAME), eq(job), requestCaptor.capture());

        ArgumentCaptor<PersistentTasksService.WaitForPersistentTaskListener> requestCaptor2
            = ArgumentCaptor.forClass(PersistentTasksService.WaitForPersistentTaskListener.class);
        doAnswer(invocation -> {
            // Bail here with an error, further testing will happen through tests of #startPersistentTask
            requestCaptor2.getValue().onFailure(new RuntimeException("Ending"));
            return null;
        }).when(tasksService).waitForPersistentTaskCondition(eq(job.getConfig().getId()), any(), any(), requestCaptor2.capture());

        TransportPutRollupJobAction.startPersistentTask(job, testListener, tasksService);
        verify(tasksService).sendStartRequest(eq(job.getConfig().getId()), eq(RollupField.TASK_NAME), eq(job), any());
        verify(tasksService).waitForPersistentTaskCondition(eq(job.getConfig().getId()), any(), any(), any());
    }
}
