# Jira Cloud Advanced Roadmap Group-By Team or Assignee Helper Label Generator
Excuse the title. I lack inspiration for something shorter.

## Motivation
As of today (Apr. 2021) Jira Cloud Advanced Roadmaps is IMHO a quite young but very promising product.
It works out well in standard view with its feature of filtering and checkbox 'show full hierarchy'.

If you have multiple teams or different assignees on epics all being beneath one same initiative, you lose hierarchy visibility when you turn on view feature "group by" team or assignee.

Reformulated:
* A Team or Assignee responsible/assigned for an initiative won't see child epics if child issues's Team or Assignee field is empty or has a different value than the value of the initiative.
* A Team or Assignee responsible/assignee for an Epic beneath this initiative won't see sibling Epics if Team or Assignee field is empty or has different value than themselves.

Result in these Group By Views:
* As an initiative responsible Team or Assignee you do not see all child issues you are responsible for
* As an epic responsible Team or Assignee you do not see sibling Epics of others and be aware about timeline and change management need of all epics that should deliver the initiative

## Mitigation
As a temporary mitigation if you already are committed to Advanced Roadmaps and have multi team initiatives this repo provides a groovy script based on the great [ScriptRunner for Jira (Cloud)](https://marketplace.atlassian.com/apps/6820/scriptrunner-for-jira?hosting=cloud&tab=overview).

It's basic strategy is to automatically generate labels on all initiative and it's subchildren issues to provide a distinct set of involved assignees and teams of the initiative hierarchy branch

The algorithms/solutions concrete strategy is:
* work event based, not as a sheduled job
* react on issue creation or change of an existing issue if field Team or Assignee changes
* fetch the responding initiative upstream the hierarchy. if none is found; halt and ignore the event
* calculate a distinct list of Teams and Assignees that are involved in all subchildren of the initiative
  * aRA.* (aRA = prefix for "advanced Roadmap (involved) Assignee"), eg. aRA.Donald-Duck, aRA.Mickey-Mouse
  * aRT.* (aRT = prefix for "advanced Roadmap (involved) Team), eg. aRT.Team-Blue, aRT.Team-Green
* iterate over every child and add or remove aRA.* or aRT.* labels if needed. if issue already has needed target set of labels, skip to next issue and spare a REST API call

Leverage then "Group by Labels" view in Adv. Roadmaps.
* Create then per label you are interested in Advanced Roadmaps View per "Group by Label" groupings with title "Initiatives with Team {Team-Blue}" or "Initiatives with {Donald-Duck}" and select beneath it the according label.
* You can also combine multiple labels into one Group, if that suits your need.

## Limitations
* Script Runners docu states 120 second timeout limit. Despite tests (~ 200+ issues with need rest api calls for each are handled within ~ 90 seconds) you may need to contact (as of docu) Script Runner Support to request to extend your default timeout limit. The script implements paging though to iterative fetch the complete resultset if it's larger than the initial result set size / page size (today = 100 issues)
* Script works only for Advanced Roadmap Shared Teams (!) not plan local Teams - script will generate then aRT.plan-local-team label
* Script handles events: issue create and issue update event, but not issue delete. In our setup we don't delete issues. If you need that; please test and give feedback. You'd need a recalculation of the whole initiative branch for sure, as you may have to remove Team or Assignee labels if they don't appear on any other issue in the initiative.
* Script can leverage only logger.info, logger.warn and logger.error levels. Scriptrunner Cloud Standard Setup seems so far to have logger.debug and logger.trace disabled (and not turn onable for oneself), hence the script can't leverage these levels, despite Scriptrunner Log UI shows them. (support call ongoing about that)
* Script has per default almost all logger statements outcommented. Scriptrunner Cloud Log UI shows only the last 300 log entries. Hence if you face issues and have to "debug", you'll have to iteratively reconstruct and replay your case and outcomment logger statements for the code parts in doubt (support call ongoing about that)

## If you like it, upvote for a real feature with atlassian and let us use great Script Runner for other tricky tasks ;)
Please upvote: https://jira.atlassian.com/browse/JSWCLOUD-21438

## Legal Disclaimer & Appreciation
* prerequisites: you have to obtain licenses of Jira Cloud, Advanced Roadmap and Scriptrunner
* all legal matters of Jira Cloud, Advanced Roadmaps and Scriptrunner are between you and their corresponding legal entities
* on top of that, the script itself is provided with no liability whatever - reuse, change freely - it's a humble contribution to the great community and great products of Atlassian and Adaptavist - hence note the LICENSE file in the repo

## How to setup
* Go to your Script Runners Config UI
* copy and paste the scripts body into two ! script listeners
* first script listener should have subscribed to event ``Issue Updated`` and have condition set to ```"issue.changelogs[0].items[0].field == "assignee" || issue.changelogs[0].items[0].field == "Team"```
* second script listener should have subscribed to event ``Issue Created`` with condition `true` (to always fire)
* set both listeners to react on those jira projects you need them to work. (should be all jira projects involved in the adv.roadmap hierarchy of your interest)
