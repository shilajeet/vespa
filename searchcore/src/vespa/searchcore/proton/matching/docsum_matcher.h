// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "search_session.h"
#include "match_tools.h"
#include <vespa/searchlib/common/featureset.h>
#include <vespa/searchlib/common/struct_field_mapper.h>
#include <vespa/searchlib/common/matching_elements.h>
#include <vector>
#include <memory>

namespace proton::matching {

/**
 * Used to perform additional matching related to a docsum
 * request. Note that external objects must be kept alive by the one
 * using this class.
 **/
class DocsumMatcher
{
private:
    using FeatureSet = search::FeatureSet;
    using StructFieldMapper = search::StructFieldMapper;
    using MatchingElements = search::MatchingElements;

    SearchSession::SP     _from_session;
    MatchToolsFactory::UP _from_mtf;
    MatchToolsFactory    *_mtf;
    std::vector<uint32_t> _docs;

public:
    DocsumMatcher();
    DocsumMatcher(SearchSession::SP session, std::vector<uint32_t> docs);
    DocsumMatcher(MatchToolsFactory::UP mtf, std::vector<uint32_t> docs);
    ~DocsumMatcher();

    using UP = std::unique_ptr<DocsumMatcher>;

    FeatureSet::UP get_summary_features() const;
    FeatureSet::UP get_rank_features() const;
    MatchingElements::UP get_matching_elements(const StructFieldMapper &field_mapper) const;
};

}
