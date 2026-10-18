export interface Actor {id:string;username:string;name:string;role:'ADMIN'|'RECORDER'}
export interface Worker {id:string;code:string;name:string;phone:string;active:boolean;group_id?:string}
export interface FarmGroup {id:string;name:string;active:boolean}
export interface Basket {id:string;name:string;weight_kg:number;active:boolean;revision?:number}
export interface Weighing {gross:string;tare:string;basketId?:string;basketWeight?:string;count?:number;grossId?:string;grossRevision?:number;basketRevision?:number}
export interface Category {id:string;name:string;active:boolean}
export interface Product {id:string;category_id:string;name:string;active:boolean}
export interface Stage {key:string;label:string;type:'PIECE'|'WEIGHT';rate:string;kgPerPiece?:string|null;limit?:string|null;min?:string|null;max?:string|null}
export interface Rule {mode:'SEGMENTS'|'ALLOCATE';stages:Stage[]}
export interface RuleVersion {id:string;product_id:string;effective_date:string;end_date:string|null;revision:number;rule_json:string;created_at:string;change_reason:string}
export interface Inputs {quantities:Record<string,string>;totalWeight:string|null;weightUnit:'kg'|'jin'|'g';weighings?:Record<string,Weighing>}
export interface GroupQuantity {record_id:string;group_id:string;group_name:string;inputs_json:string}
export interface Entry {mode:'ADD'|'CORRECT'|'MOVE';groupId:string;inputs:Inputs;plotId?:string;targetPlotId?:string;targetGroupId?:string}
export interface Quote {amount:number;parts:{label:string;type:string;quantity:number;rate:number;amount:number}[];remainderKg:number}
export interface Harvest {id:string;worker_id:string;work_date:string;period:'AM'|'PM';product_id:string;inputs_json:string;note:string;created_by:string;updated_by:string;updated_name:string;creator_name:string;version:number;deleted:boolean;settled?:boolean;worker_name:string;worker_code:string;product_name:string;quote?:Quote;pricing_error?:string;localState?:string;groups?:GroupQuantity[];allocations?:PlotQuantity[];costs?:PlotCost[];rule?:Rule}
export interface Operation {operationId:string;action:'CREATE'|'UPDATE'|'DELETE'|'RESTORE';recordId:string;workerId:string;workDate:string;period:'AM'|'PM';productId:string;inputs:Inputs;note:string;version:number|null;reason:string;entry?:Entry}
export interface SyncResult {status:string;message:string;record?:Harvest}
export interface Pending {operationId:string;userId:string;operation:Operation;state:'pending'|'conflict'|'rejected'|'sending';message:string;createdAt:number;attempted?:boolean;result?:SyncResult}
export interface Bootstrap {workers:Worker[];categories:Category[];products:Product[];rules:RuleVersion[];today:string;user:Actor;groups?:FarmGroup[];baskets?:Basket[];plots?:FarmGroup[];grossWeights?:Basket[]}
export interface PayWorker {workerId:string;code:string;name:string;pieces:number;weightKg:number;earned:number;confirmed:number;settled:number;pending:number;paid:number;unpaid:number}
export interface Payroll {month:string;closed:boolean;workers:PayWorker[];lines:{record:Harvest;amount:number;settled:number;delta:number}[];settlements:{id:string;kind:string;created_at:string}[];payments:{id:string;worker_name:string;amount:number;method:string;note:string;created_at:string}[]}

export interface PlotQuantity extends GroupQuantity {plot_id:string;plot_name:string}
export interface PlotCost extends PlotQuantity {amount:number}
export interface Plan {id:string;work_date:string;period:'AM'|'PM';worker_id:string;worker_name:string;worker_code:string;group_id:string;group_name:string;product_id:string;product_name:string;plot_id:string;plot_name:string;resolution:string;reason:string;destination:string;version:number;state?:string;needsReview?:boolean;updated_name?:string}
export interface PlanChange {operationId:string;id:string;version:number|null;date:string;period:'AM'|'PM';workerId:string;groupId:string;productId:string;plotId:string;resolution:string;reason:string;destination:Record<string,string>}
export interface PlanPending {operation:PlanChange;state:'pending'|'conflict'|'rejected';message:string;attempted:boolean;result?:{status:string;message:string;plan:Plan}}
