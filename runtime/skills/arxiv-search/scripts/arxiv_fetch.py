import arxiv
import datetime

topics = {
    "LLM_agents_general": "large language model autonomous agents",
    "multi_agent_systems": "multi-agent systems large language models collaboration",
    "agentic_AI_tool_use": "agentic AI tool use function calling reasoning",
    "coding_agents": "LLM agents software engineering code generation",
    "agent_memory_planning": "LLM agent memory planning reasoning survey",
    "agent_safety_eval": "evaluation safety large language model agents benchmarks",
}

client = arxiv.Client()

for key, query in topics.items():
    print("=" * 80)
    print(f"### TOPIC: {key} -> {query}")
    print("=" * 80)
    try:
        search = arxiv.Search(
            query=query,
            max_results=6,
            sort_by=arxiv.SortCriterion.SubmittedDate,
            sort_order=arxiv.SortOrder.Descending,
        )
        for paper in client.results(search):
            d = paper.published.date().isoformat()
            authors = ", ".join(a.name for a in paper.authors[:3])
            if len(paper.authors) > 3:
                authors += " et al."
            print(f"[{d}] {paper.title}")
            print(f"    Authors: {authors}")
            print(f"    URL: {paper.entry_id}")
            summary = paper.summary.replace("\n", " ")[:400]
            print(f"    Abstract: {summary}")
            print()
    except Exception as e:
        print(f"ERROR ({key}): {e}")
    print()