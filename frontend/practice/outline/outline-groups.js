// Group identity is the author's outline array index, not the title or extension type.
export function consecutiveQuestionGroups(questions){
  const groups=[];
  for(const [index,question] of questions.entries()){
    const groupIndex=question.outlineGroupIndex??null;
    let group=groups.at(-1);
    if(!group||group.index!==groupIndex){
      group={index:groupIndex,name:question.outlineGroupName??null,items:[]};groups.push(group);
    }
    group.items.push({question,number:index+1});
  }
  return groups;
}
