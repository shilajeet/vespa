// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/vespalib/util/executor.h>
#include <vespa/vespalib/stllike/hash_fun.h>
#include <vespa/vespalib/util/lambdatask.h>

namespace search {

/**
 * Interface class to run multiple tasks in parallel, but tasks with same
 * id has to be run in sequence.
 */
class ISequencedTaskExecutor
{
public:
    class ExecutorId {
    public:
        ExecutorId() : ExecutorId(0) { }
        explicit ExecutorId(uint32_t id) : _id(id) { }
        uint32_t getId() const { return _id; }
        bool operator != (ExecutorId rhs) const { return _id != rhs._id; }
        bool operator == (ExecutorId rhs) const { return _id == rhs._id; }
        bool operator < (ExecutorId rhs) const { return _id < rhs._id; }
    private:
        uint32_t _id;
    };
    ISequencedTaskExecutor(uint32_t numExecutors) : _numExecutors(numExecutors) { }
    virtual ~ISequencedTaskExecutor() { }

    /**
     * Calculate which executor will handle an component. All callers
     * must be in the same thread.
     *
     * @param componentId   component id
     * @return              executor id
     */
    ExecutorId getExecutorId(uint64_t componentId) const {
        return ExecutorId((componentId * 1099511628211ULL ) % _numExecutors);
    }
    uint32_t getNumExecutors() const { return _numExecutors; }

    ExecutorId getExecutorId(vespalib::stringref componentId) const {
        vespalib::hash<vespalib::stringref> hashfun;
        return getExecutorId(hashfun(componentId));
    }

    /**
     * Schedule a task to run after all previously scheduled tasks with
     * same id.
     *
     * @param id     which internal executor to use
     * @param task   unique pointer to the task to be executed
     */
    virtual void executeTask(ExecutorId id, vespalib::Executor::Task::UP task) = 0;

    /**
     * Wrap lambda function into a task and schedule it to be run.
     * Caller must ensure that pointers and references are valid and
     * call sync before tearing down pointed to/referenced data.
      *
     * @param id        which internal executor to use
     * @param function  function to be wrapped in a task and later executed
     */
    template <class FunctionType>
    void executeLambda(ExecutorId id, FunctionType &&function) {
        executeTask(id, vespalib::makeLambdaTask(std::forward<FunctionType>(function)));
    }
    /**
     * Wait for all scheduled tasks to complete.
     */
    virtual void sync() = 0;

    /**
     * Wrap lambda function into a task and schedule it to be run.
     * Caller must ensure that pointers and references are valid and
     * call sync before tearing down pointed to/referenced data.
     * All tasks must be scheduled from same thread.
     *
     * @param componentId   component id
     * @param function      function to be wrapped in a task and later executed
     */
    template <class FunctionType>
    void execute(uint64_t componentId, FunctionType &&function) {
        ExecutorId id = getExecutorId(componentId);
        executeTask(id, vespalib::makeLambdaTask(std::forward<FunctionType>(function)));
    }

    /**
     * Wrap lambda function into a task and schedule it to be run.
     * Caller must ensure that pointers and references are valid and
     * call sync before tearing down pointed to/referenced data.
     * All tasks must be scheduled from same thread.
     *
     * @param componentId   component id
     * @param function      function to be wrapped in a task and later executed
     */
    template <class FunctionType>
    void execute(vespalib::stringref componentId, FunctionType &&function) {
        ExecutorId id = getExecutorId(componentId);
        executeTask(id, vespalib::makeLambdaTask(std::forward<FunctionType>(function)));
    }
private:
    uint32_t _numExecutors;
};

} // namespace search
