class SearchGateway {

    void fetchFirstPage() {
        SearchResponse searchResponse = searchRequestBuilder
            .executeSearch(searchCriteriaWithHighlighting, pageSettings); // cached
    }
}
