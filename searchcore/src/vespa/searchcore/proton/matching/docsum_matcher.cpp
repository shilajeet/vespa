// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "docsum_matcher.h"
#include <vespa/eval/eval/tensor.h>
#include <vespa/eval/eval/tensor_engine.h>
#include <vespa/vespalib/objects/nbostream.h>
#include <vespa/searchcommon/attribute/i_search_context.h>
#include <vespa/searchlib/queryeval/blueprint.h>
#include <vespa/searchlib/queryeval/intermediate_blueprints.h>
#include <vespa/searchlib/queryeval/same_element_blueprint.h>
#include <vespa/searchlib/queryeval/same_element_search.h>

#include <vespa/log/log.h>
LOG_SETUP(".proton.matching.docsum_matcher");

using search::FeatureSet;
using search::MatchingElements;
using search::StructFieldMapper;
using search::fef::FeatureResolver;
using search::fef::RankProgram;
using search::queryeval::AndNotBlueprint;
using search::queryeval::Blueprint;
using search::queryeval::IntermediateBlueprint;
using search::queryeval::SameElementBlueprint;
using search::queryeval::SearchIterator;

using AttrSearchCtx = search::attribute::ISearchContext;

namespace proton::matching {

namespace {

FeatureSet::UP get_feature_set(const MatchToolsFactory &mtf,
                               const std::vector<uint32_t> &docs,
                               bool summaryFeatures)
{
    MatchTools::UP matchTools = mtf.createMatchTools();
    if (summaryFeatures) {
        matchTools->setup_summary();
    } else {
        matchTools->setup_dump();
    }
    RankProgram &rankProgram = matchTools->rank_program();

    std::vector<vespalib::string> featureNames;
    FeatureResolver resolver(rankProgram.get_seeds(false));
    featureNames.reserve(resolver.num_features());
    for (size_t i = 0; i < resolver.num_features(); ++i) {
        featureNames.emplace_back(resolver.name_of(i));
    }
    auto retval = std::make_unique<FeatureSet>(featureNames, docs.size());
    if (docs.empty()) {
        return retval;
    }
    FeatureSet &fs = *retval;

    SearchIterator &search = matchTools->search();
    search.initRange(docs.front(), docs.back()+1);
    for (uint32_t i = 0; i < docs.size(); ++i) {
        if (search.seek(docs[i])) {
            uint32_t docId = search.getDocId();
            search.unpack(docId);
            auto * f = fs.getFeaturesByIndex(fs.addDocId(docId));
            for (uint32_t j = 0; j < featureNames.size(); ++j) {
                if (resolver.is_object(j)) {
                    auto obj = resolver.resolve(j).as_object(docId);
                    if (const auto *tensor = obj.get().as_tensor()) {
                        vespalib::nbostream buf;
                        tensor->engine().encode(*tensor, buf);
                        f[j].set_data(vespalib::Memory(buf.peek(), buf.size()));
                    } else {
                        f[j].set_double(obj.get().as_double());
                    }
                } else {
                    f[j].set_double(resolver.resolve(j).as_number(docId));
                }
            }
        } else {
            LOG(debug, "getFeatureSet: Did not find hit for docid '%u'. Skipping hit", docs[i]);
        }
    }
    if (auto onSummaryTask = mtf.createOnSummaryTask()) {
        onSummaryTask->run(docs);
    }
    return retval;
}

template<typename T>
const T *as(const Blueprint &bp) { return dynamic_cast<const T *>(&bp); }

void find_matching_elements(const std::vector<uint32_t> &docs, const SameElementBlueprint &same_element, MatchingElements &result) {
    auto search = same_element.create_same_element_search(false);
    search->initRange(docs.front(), docs.back()+1);
    std::vector<uint32_t> matches;
    for (uint32_t i = 0; i < docs.size(); ++i) {
        search->find_matching_elements(docs[i], matches);
        if (!matches.empty()) {
            result.add_matching_elements(docs[i], same_element.struct_field_name(), matches);
            matches.clear();
        }
    }
}

void find_matching_elements(const std::vector<uint32_t> &docs, const vespalib::string &struct_field_name, const AttrSearchCtx &attr_ctx, MatchingElements &result) {
    int32_t weight = 0;
    std::vector<uint32_t> matches;
    for (uint32_t i = 0; i < docs.size(); ++i) {
        for (int32_t id = attr_ctx.find(docs[i], 0, weight); id >= 0; id = attr_ctx.find(docs[i], id+1, weight)) {
            matches.push_back(id);
        }
        if (!matches.empty()) {
            result.add_matching_elements(docs[i], struct_field_name, matches);
            matches.clear();
        }
    }
}

void find_matching_elements(const StructFieldMapper &mapper, const std::vector<uint32_t> &docs, const Blueprint &bp, MatchingElements &result) {
    if (auto same_element = as<SameElementBlueprint>(bp)) {
        if (mapper.is_struct_field(same_element->struct_field_name())) {
            find_matching_elements(docs, *same_element, result);
        }
    } else if (const AttrSearchCtx *attr_ctx = bp.get_attribute_search_context()) {
        if (mapper.is_struct_subfield(attr_ctx->attributeName())) {
            find_matching_elements(docs, mapper.get_struct_field(attr_ctx->attributeName()), *attr_ctx, result);
        }
    } else if (auto and_not = as<AndNotBlueprint>(bp)) {
        find_matching_elements(mapper, docs, and_not->getChild(0), result);
    } else if (auto intermediate = as<IntermediateBlueprint>(bp)) {
        for (size_t i = 0; i < intermediate->childCnt(); ++i) {
            find_matching_elements(mapper, docs, intermediate->getChild(i), result);
        }
    }
}

}

DocsumMatcher::DocsumMatcher()
    : _from_session(),
      _from_mtf(),
      _mtf(nullptr),
      _docs()
{
}

DocsumMatcher::DocsumMatcher(SearchSession::SP session, std::vector<uint32_t> docs)
    : _from_session(std::move(session)),
      _from_mtf(),
      _mtf(&_from_session->getMatchToolsFactory()),
      _docs(std::move(docs))
{
}

DocsumMatcher::DocsumMatcher(MatchToolsFactory::UP mtf, std::vector<uint32_t> docs)
    : _from_session(),
      _from_mtf(std::move(mtf)),
      _mtf(_from_mtf.get()),
      _docs(std::move(docs))
{
}

DocsumMatcher::~DocsumMatcher() {
    if (_from_session) {
        _from_session->releaseEnumGuards();
    }
}

FeatureSet::UP
DocsumMatcher::get_summary_features() const
{
    if (!_mtf) {
        return std::make_unique<FeatureSet>();
    }
    return get_feature_set(*_mtf, _docs, true);
}

FeatureSet::UP
DocsumMatcher::get_rank_features() const
{
    if (!_mtf) {
        return std::make_unique<FeatureSet>();
    }
    return get_feature_set(*_mtf, _docs, false);
}

MatchingElements::UP
DocsumMatcher::get_matching_elements(const StructFieldMapper &field_mapper) const
{
    auto result = std::make_unique<MatchingElements>();
    if (_mtf && !field_mapper.empty()) {
        if (const Blueprint *root = _mtf->query().peekRoot()) {
            find_matching_elements(field_mapper, _docs, *root, *result);
        }
    }
    return result;
}

}
