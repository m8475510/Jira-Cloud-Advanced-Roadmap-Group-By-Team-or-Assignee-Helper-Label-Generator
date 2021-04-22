//logger.info("/////////////////////////////////////////////////////////////")
//logger.info("//////// fetching custom ids of custom fields needed for api")
//logger.info("/////////////////////////////////////////////////////////////")

// Get the Id for the custom fields "Epic Link", "Parent Link" and Team (that comes with Adv.Roadmaps)
def customFields = get("/rest/api/2/field").asObject(List).body.findAll { (it as Map).custom } as List<Map>
def epicLinkId = customFields.find {it.name == 'Epic Link'}?.id
def parentLinkId = customFields.find {it.name == 'Parent Link'}?.id
def teamFieldId = customFields.find {it.name == 'Team'}?.id

//logger.info("customField 'Epic Link' id: ${epicLinkId}")
//logger.info("customField 'Parent Link' id: ${parentLinkId}")
//logger.info("customField 'Team' id: = ${teamFieldId}")

//logger.info("/////////////////////////////////////////////////////////////")
//logger.info("//////// searching for initiative of issue and traversing upwards hierarchy if needed")
//logger.info("/////////////////////////////////////////////////////////////")

def issueToCheck = issue
for(def i = 0; i < 10; i++ )
{
    if(issueToCheck.fields["issuetype"].name == null) throw new Exception("IssueType Name null")
    //else logger.info("${issueToCheck.key}: issutype: " + issueToCheck.fields["issuetype"].name)

    if(issueToCheck.fields["issuetype"].name == "Initiative" || issueToCheck.fields["issuetype"].name == "Stream" ) {
        //logger.info("issutype initiative or stream found -> stopping traversing")
        break
    }

    def parentKey = findParentIssueKey(issueToCheck as Map, parentLinkId, epicLinkId)
    if (parentKey == null) {
        logger.info("${issueToCheck.key}: parentkey: no parentKey found. hierarchy chain upstream ends here. halting...")
        return
    }

    //logger.info("fetching parent issue")
    issueToCheck = getIssue(parentKey, parentLinkId, epicLinkId)

    if(i == 9) throw new Exception("something must be wrong. traversing too much upwards: iteratin limit: ${i+1}")
}

if(issueToCheck.fields["issuetype"].name != "Initiative") {
    logger.info("${issueToCheck.key}: after traversal issuetype not Initiative. halting...")
    return
}

//logger.info("/////////////////////////////////////////////////////////////")
//logger.info("//////// Initiative found. starting reprocessing generated labels the whole hierarchy beneath it.")
//logger.info("/////////////////////////////////////////////////////////////")

def prefixTeamLabels = "aRT"
def prefixAssigneeLabels = "aRA"

def initiativeKeyToProcess = issueToCheck.key

// query all hierarchy child issues of initiative and initiative itself
def query = "issuekey = \"${initiativeKeyToProcess}\" or issuekey in portfolioChildIssuesOf(\"${initiativeKeyToProcess}\")"

// do the search and request only the fields in result we actually need
def searchReq = get("/rest/api/2/search")
        .queryString("jql", query)
        .queryString("maxResults", 2147483647)
        // int32.max
        // see (https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-search/#api-rest-api-3-search-get) > default 50 > could be problem therefore max value explicitly
        // see https://community.atlassian.com/t5/Jira-questions/how-to-alter-the-number-of-maxResults-on-a-search-jql-REST-API/qaq-p/684442
        // hence request a maximum and then do pagination based on maxResults as pagins step size
        // see https://developer.atlassian.com/cloud/jira/platform/rest/v3/intro/#pagination
        // "To find the maximum number of items that an operation could return, set maxResults to a large number—for example, over 1000—and if the returned value of maxResults is less than the requested value, the returned value is the maximum."
        .queryString('fields', "${teamFieldId},assignee,labels")
        .asObject(Map)

// Verify the search completed successfully
assert searchReq.status == 200
// Save the search results as a Map
Map searchResult = searchReq.body

def issuescollection = searchResult.issues.collect()

if((searchResult.startAt == 0) && (searchResult.maxResults > 0) && (searchResult.total > searchResult.maxResults))
{
    //we need pagination and fetch all issues repeatedly
    logger.info("pagination needed! searchResult.total: ${searchResult.total}")
    for (def i = 1; i <= (searchResult.total / searchResult.maxResults); i++){
        logger.info("startAt: ${searchResult.startAt}, maxResults: ${searchResult.maxResults}, total: ${searchResult.total}")
        searchReq = get("/rest/api/2/search")
                .queryString("jql", query)
                .queryString("startAt", searchResult.maxResults * i)
                .queryString("maxResults", searchResult.maxResults)
                .queryString('fields', "${teamFieldId},assignee,labels")
                .asObject(Map)

        assert searchReq.status == 200
        searchResult = searchReq.body
        logger.info(searchResult.toString())

        issuescollection.addAll(searchResult.issues.collect())
    }
}
else
    logger.info("no pagination needed!")

if(issuescollection.size() != searchResult.total) throw new Exception("Paging issuecollection size != initial searchresult total. Something went wrong")

// Log Out ResultSet
//issuescollection.each { Map issue ->
    //logger.info("issue found ${issue.key} with team: '${issue.fields[teamFieldId] ? issue.fields[teamFieldId].title : null}', assignee: '${issue.fields["assignee"] ? issue.fields["assignee"].displayName : null}', labels: ${issue.fields["labels"]}")
//}
//logger.info("/////////////////////////////////////////////////////////////")
//logger.info("//////// creating fresh group by teams and assignees, as well as label set")
//logger.info("/////////////////////////////////////////////////////////////")

// Group by Team
def resultByTeam = issuescollection
        .findAll({issue -> issue.fields[teamFieldId] != null})
        .groupBy({issue -> issue.fields[teamFieldId].title})
        .keySet() as List

//logger.info("new 'grouped by team': ${resultByTeam}")

// Group by Assignee
def resultByAssignee = issuescollection
        .findAll({issue -> issue.fields["assignee"] != null})
        .groupBy({issue -> issue.fields["assignee"].displayName})
        .keySet() as List

//logger.info("new 'grouped by assignee': ${resultByAssignee}")

// generate now label set
List<String> newTeamLabels = resultByTeam.collect {team -> "${prefixTeamLabels}.${team.replaceAll("\\s","-")}"}
//logger.info("new TeamLabels generated: ${newTeamLabels}")

List<String> newAssigneeLabels = resultByAssignee.collect {team -> "${prefixAssigneeLabels}.${team.replaceAll("\\s","-")}"}
//logger.info("new AssigneeLabels generated: ${newAssigneeLabels}")

//logger.info("/////////////////////////////////////////////////////////////")
//logger.info("//////// now add and remove labels to the whole initiative branch")
//logger.info("/////////////////////////////////////////////////////////////")

//logger.info("Reprocessing now initiative ${initiativeKeyToProcess} and adding, removing or keeping labels for teams and assignees according to new state.")

def labelCommands = []

for (Map issue in issuescollection) {

    labelCommands.clear()
    generateLabelCommands(labelCommands, issue, prefixTeamLabels, newTeamLabels)
    generateLabelCommands(labelCommands, issue, prefixAssigneeLabels, newAssigneeLabels)

    //now to the label update rest api call for the issue
    if(!labelCommands.empty) {
        def result = put('/rest/api/2/issue/' + issue.key)
                .header('Content-Type', 'application/json')
                .body([
                        update: [
                                labels: labelCommands
                        ]
                ])
                .asString()

        if (result.status == 204) {
            //logger.info("${issue.key}: labelCommands: ${labelCommands} ... OK")
        } else {
            //logger.info("${issue.key}: labelCommands: ${labelCommands} ... ERROR: ${result.status}: ${result.body}")
        }
    }
    else logger.info("${issue.key}: labelCommands: ${labelCommands} ... empty > issue skipped, no change needed")

}

//// helper methods definitions:
Map getIssue(String issueKey, parent_link_Id, epic_link_Id) {
    def result = get('/rest/api/2/issue/' + issueKey)
            .header('Content-Type', 'application/json')
            .queryString('fields', "issuetype, parent, ${epic_link_Id}, ${parent_link_Id}")
            .asObject(Map)
    if (result.status == 200) {
        return result.body as Map
    } else {
        throw new Exception("Failed to find issue ${issueKey}: Status: ${result.status} ${result.body}")
    }
}
String findParentIssueKey(Map i, parent_link_Id, epic_link_Id) {
    def parentIssue = i.fields["parent"]?.key
    if (parentIssue != null) {
        //logger.info("${i.key}: found parent ${parentIssue} in field 'parent')")
        return parentIssue
    }
    else {
        parentIssue = i.fields[parent_link_Id].data?.key
        if (parentIssue != null) {
            //logger.info("${i.key}: found parent ${parentIssue} in field '${parentLinkId}'")
            return parentIssue
        }
        else {
            parentIssue = i.fields[epic_link_Id]?.toString()
            if (parentIssue != null) {
                //logger.info("${i.key}: found parent ${parentIssue} in field '${epicLinkId}')")
                return parentIssue
            }
            else return null
        }
    }
}
void generateLabelCommands(ArrayList labelCommands, Map issue, String prefixOfLabels, List<String> newLabels) {

    // remove labels with matching prefix that are excess due to change
    // (eg. initiative nor any child issue is assigned to team A anymore -> label for team A must be removed,
    // if team a has been assigned before the change anywhere)
    for (issueLabel in issue.fields["labels"]) {
        if (issueLabel.startsWith(prefixOfLabels) && !newLabels.any { l -> l == issueLabel }) {
            labelCommands.add([remove: issueLabel])

        }
    }
    // add new missing Labels or skip existing matches
    for (newLabel in (newLabels)) {
        if (!issue.fields["labels"].any { issueLabel -> issueLabel == newLabel }) {
            labelCommands.add([add: newLabel])
        }
    }
}


return "Script Completed - Check the Logs tab for information on which issues were updated."
