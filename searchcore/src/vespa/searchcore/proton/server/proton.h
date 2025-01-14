// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "bootstrapconfig.h"
#include "bootstrapconfigmanager.h"
#include "documentdb.h"
#include "health_adapter.h"
#include "i_proton_configurer_owner.h"
#include "idocumentdbowner.h"
#include "memory_flush_config_updater.h"
#include "proton_config_fetcher.h"
#include "proton_configurer.h"
#include "rpc_hooks.h"
#include <vespa/searchcore/proton/matching/querylimiter.h>
#include <vespa/searchcore/proton/metrics/metrics_engine.h>
#include <vespa/searchcore/proton/persistenceengine/i_resource_write_filter.h>
#include <vespa/searchcore/proton/persistenceengine/ipersistenceengineowner.h>
#include <vespa/searchcore/proton/persistenceengine/persistenceengine.h>
#include <vespa/searchlib/common/fileheadercontext.h>
#include <vespa/searchlib/engine/monitorapi.h>
#include <vespa/searchlib/transactionlog/translogserverapp.h>
#include <vespa/vespalib/net/component_config_producer.h>
#include <vespa/vespalib/net/generic_state_handler.h>
#include <vespa/vespalib/net/json_get_handler.h>
#include <vespa/vespalib/net/json_handler_repo.h>
#include <vespa/vespalib/net/state_explorer.h>
#include <vespa/vespalib/util/varholder.h>
#include <mutex>
#include <shared_mutex>

namespace vespalib { class StateServer; }

namespace proton {

class DiskMemUsageSampler;
class IDocumentDBReferenceRegistry;
class IProtonDiskLayout;
class PrepareRestartHandler;
class SummaryEngine;
class DocsumBySlime;
class FlushEngine;
class MatchEngine;

class Proton : public IProtonConfigurerOwner,
               public search::engine::MonitorServer,
               public IDocumentDBOwner,
               public StatusProducer,
               public IPersistenceEngineOwner,
               public vespalib::ComponentConfigProducer,
               public vespalib::StateExplorer
{
private:
    typedef search::transactionlog::TransLogServerApp     TLS;
    typedef search::engine::MonitorRequest                MonitorRequest;
    typedef search::engine::MonitorReply                  MonitorReply;
    typedef search::engine::MonitorClient                 MonitorClient;
    typedef std::map<DocTypeName, DocumentDB::SP>         DocumentDBMap;
    typedef BootstrapConfig::ProtonConfigSP               ProtonConfigSP;
    using InitializeThreads = std::shared_ptr<vespalib::ThreadStackExecutorBase>;
    using BucketSpace = document::BucketSpace;

    struct MetricsUpdateHook : metrics::UpdateHook
    {
        Proton &self;
        MetricsUpdateHook(Proton &s)
            : metrics::UpdateHook("proton-hook"),
              self(s) {}
        void updateMetrics(const vespalib::MonitorGuard &guard) override { self.updateMetrics(guard); }
    };
    friend struct MetricsUpdateHook;

    class ProtonFileHeaderContext : public search::common::FileHeaderContext
    {
        vespalib::string _hostName;
        vespalib::string _creator;
        vespalib::string _cluster;
        pid_t _pid;

    public:
        ProtonFileHeaderContext(const Proton &proton_, const vespalib::string &creator);
        ~ProtonFileHeaderContext() override;

        void addTags(vespalib::GenericHeader &header, const vespalib::string &name) const override;
        void setClusterName(const vespalib::string &clusterName, const vespalib::string &baseDir);
    };

    const config::ConfigUri         _configUri;
    mutable std::shared_timed_mutex _mutex;
    MetricsUpdateHook               _metricsHook;
    std::unique_ptr<MetricsEngine>  _metricsEngine;
    ProtonFileHeaderContext         _fileHeaderContext;
    TLS::UP                         _tls;
    std::unique_ptr<DiskMemUsageSampler> _diskMemUsageSampler;
    PersistenceEngine::UP           _persistenceEngine;
    DocumentDBMap                   _documentDBMap;
    std::unique_ptr<MatchEngine>   _matchEngine;
    std::unique_ptr<SummaryEngine>  _summaryEngine;
    std::unique_ptr<DocsumBySlime>  _docsumBySlime;
    MemoryFlushConfigUpdater::UP    _memoryFlushConfigUpdater;
    std::unique_ptr<FlushEngine>    _flushEngine;
    std::unique_ptr<PrepareRestartHandler> _prepareRestartHandler;
    RPCHooks::UP                    _rpcHooks;
    HealthAdapter                   _healthAdapter;
    vespalib::GenericStateHandler   _genericStateHandler;
    vespalib::JsonHandlerRepo::Token::UP _customComponentBindToken;
    vespalib::JsonHandlerRepo::Token::UP _customComponentRootToken;
    std::unique_ptr<vespalib::StateServer>  _stateServer;
    vespalib::ThreadStackExecutor   _executor;
    std::unique_ptr<IProtonDiskLayout> _protonDiskLayout;
    ProtonConfigurer                _protonConfigurer;
    ProtonConfigFetcher             _protonConfigFetcher;
    std::unique_ptr<vespalib::ThreadStackExecutorBase> _warmupExecutor;
    std::unique_ptr<vespalib::ThreadStackExecutorBase> _sharedExecutor;
    matching::QueryLimiter          _queryLimiter;
    vespalib::Clock                 _clock;
    FastOS_ThreadPool               _threadPool;
    uint32_t                        _distributionKey;
    bool                            _isInitializing;
    bool                            _isReplayDone;
    bool                            _abortInit;
    bool                            _initStarted;
    bool                            _initComplete;
    bool                            _initDocumentDbsInSequence;
    std::shared_ptr<IDocumentDBReferenceRegistry> _documentDBReferenceRegistry;
    std::mutex                      _nodeUpLock;
    std::set<BucketSpace>           _nodeUp;   // bucketspaces where node is up

    std::shared_ptr<DocumentDBConfigOwner>
    addDocumentDB(const DocTypeName & docTypeName, BucketSpace bucketSpace, const vespalib::string & configid,
                  const BootstrapConfig::SP & bootstrapConfig, const std::shared_ptr<DocumentDBConfig> &documentDBConfig,
                  InitializeThreads initializeThreads) override;

    void removeDocumentDB(const DocTypeName &docTypeName) override;

    void applyConfig(const BootstrapConfig::SP & configSnapshot) override;
    MonitorReply::UP ping(MonitorRequest::UP request, MonitorClient &client) override;

    /**
     * Called by the metrics update hook (typically in the context of
     * the metric manager). Do not call this function in multiple
     * threads at once.
     **/
    void updateMetrics(const vespalib::MonitorGuard &guard);
    void waitForInitDone();
    void waitForOnlineState();
    uint32_t getDistributionKey() const override { return _distributionKey; }
    BootstrapConfig::SP getActiveConfigSnapshot() const;
    std::shared_ptr<IDocumentDBReferenceRegistry> getDocumentDBReferenceRegistry() const override;
    bool updateNodeUp(BucketSpace bucketSpace, bool nodeUpInBucketSpace);
    void closeDocumentDBs(vespalib::ThreadStackExecutorBase & executor);
public:
    typedef std::unique_ptr<Proton> UP;
    typedef std::shared_ptr<Proton> SP;

    Proton(const config::ConfigUri & configUri,
           const vespalib::string &progName,
           uint64_t subscribeTimeout);
    ~Proton() override;

    /**
     * This method must be called after the constructor and before the destructor.
     * If not I will force a 'core' upon you.
     * All relevant initialization is conducted here.
     *
     * 1st phase init: start cheap clock thread and get initial config
     */
    BootstrapConfig::SP init();

    // 2nd phase init: setup data structures.
    void init(const BootstrapConfig::SP & configSnapshot);

    DocumentDB::SP
    addDocumentDB(const document::DocumentType &docType, BucketSpace bucketSpace, const BootstrapConfig::SP &configSnapshot,
                  const std::shared_ptr<DocumentDBConfig> &documentDBConfig, InitializeThreads initializeThreads);

    metrics::MetricManager & getMetricManager() { return _metricsEngine->getManager(); }
    FastOS_ThreadPool & getThreadPool() { return _threadPool; }

    bool triggerFlush();
    bool prepareRestart();

    void getComponentConfig(Consumer &consumer) override;
    void setClusterState(BucketSpace bucketSpace, const storage::spi::ClusterState &calc) override;

    // Return the oldest active config generation used by proton.
    int64_t getConfigGeneration();

    size_t getNumDocs() const;
    size_t getNumActiveDocs() const;
    DocsumBySlime & getDocsumBySlime() { return *_docsumBySlime; }

    search::engine::SearchServer &get_search_server();
    search::engine::DocsumServer &get_docsum_server();
    search::engine::MonitorServer &get_monitor_server();

    vespalib::string getDelayedConfigs() const;

    StatusReport::List getStatusReports() const override;

    MatchEngine & getMatchEngine() { return *_matchEngine; }
    vespalib::ThreadStackExecutorBase & getExecutor() { return _executor; }

    bool isInitializing() const override { return _isInitializing; }

    bool hasAbortedInit() const { return _abortInit; }
    storage::spi::PersistenceProvider & getPersistence() { return *_persistenceEngine; }

    void get_state(const vespalib::slime::Inserter &inserter, bool full) const override;
    std::vector<vespalib::string> get_children_names() const override;
    std::unique_ptr<vespalib::StateExplorer> get_child(vespalib::stringref name) const override;
};

} // namespace proton
